"""
Qwen3-Embedding-0.6B int8 ONNX embedding sidecar (WIKI4AI-34, epic WIKI4AI-26).

Endpoints:
    POST /embed  {"texts": [...], "type": "document"|"query"} -> {"vectors": [[...1024 floats]], ...}
    GET  /health -> {"status": "ok", "model": "Qwen3-Embedding-0.6B-int8", "dim": 1024}

Implementation notes (empirically verified, see research doc §9):
- ONNX graph = causal-LM export (Transformers.js format) from
  onnx-community/Qwen3-Embedding-0.6B-ONNX (onnx/model_int8.onnx, ~614 MB).
  Inputs: input_ids / position_ids (+ optional attention_mask) and 56x
  past_key_values.* — we feed EMPTY past KV caches and take last_hidden_state.
- Pooling: LAST-REAL-TOKEN pooling + L2 normalization (dim=1024, ||v|| = 1).
  We right-pad within a batch and gather each row's last real token, which is
  correct under causal attention even if the graph has no attention_mask input.
- Truncation at 512 tokens; batches are capped at 4 (larger requests are
  chunked server-side); ORT runs with enable_cpu_mem_arena=false and
  OMP_NUM_THREADS=2 (RAM ceiling: container cap 2.5 GB).
- Query texts get the instruction prefix from the model card:
    "Instruct: Given a web search query, retrieve relevant passages that answer the query\nQuery:{query}"
  Document texts are embedded as-is (title + "\n" + body is assembled by the caller).
"""

import logging
import os
import threading

import numpy as np
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field
from tokenizers import Tokenizer

# NOTE: onnxruntime is imported lazily inside _build_session() so that the
# unit-test mode (EMBED_SKIP_MODEL_LOAD=1) does not require the ~200 MB ORT
# package to be installed. In production it is always available (Dockerfile).

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("embedding-sidecar")

MODEL_DIR = os.environ.get("MODEL_DIR", "/models")
MODEL_FILE = os.path.join(MODEL_DIR, "model_int8.onnx")
TOKENIZER_FILE = os.path.join(MODEL_DIR, "tokenizer.json")

MAX_TOKENS = int(os.environ.get("EMBED_MAX_TOKENS", "512"))
MAX_BATCH = int(os.environ.get("EMBED_MAX_BATCH", "4"))
NUM_THREADS = int(os.environ.get("OMP_NUM_THREADS", "2"))
MODEL_NAME = "Qwen3-Embedding-0.6B-int8"

# Instruction prefix for queries (official Qwen3-Embedding model card format).
QUERY_INSTRUCT = (
    "Instruct: Given a web search query, retrieve relevant passages that answer the query\nQuery:"
)


class EmbedRequest(BaseModel):
    texts: list[str] = Field(min_length=1, description="Texts to embed")
    type: str = Field(default="document", pattern="^(document|query)$")


class EmbedResponse(BaseModel):
    vectors: list[list[float]]
    dim: int
    model: str
    truncated: bool = False


# ---------------------------------------------------------------------------
# Model loading (module level — the HTTP server starts only after this succeeds)
# ---------------------------------------------------------------------------

def _build_session():
    """Create the ORT session. onnxruntime is imported here (not at module
    level) so unit tests can run without the package installed."""
    import onnxruntime as ort  # noqa: PLC0415 — deliberate lazy import

    opts = ort.SessionOptions()
    # Required by the RAM budget decision (WIKI4AI-26): no persistent arena.
    opts.enable_cpu_mem_arena = False
    opts.intra_op_num_threads = NUM_THREADS
    opts.inter_op_num_threads = 1
    session = ort.InferenceSession(MODEL_FILE, sess_options=opts, providers=["CPUExecutionProvider"])
    return session


# Official Qwen3 pad token (see tokenizer_config.json: pad_token = "< |endoftext|>").
# It is a real special token in the embedding table (id 151643), so it is safe for
# ONNX embedding lookup and never appears in normal text. Vocab id 0 is "!" — a REAL
# token — so it must NOT be used as the pad id.
QWEN3_PAD_TOKEN = "< |endoftext|>"


def _build_tokenizer() -> tuple[Tokenizer, int]:
    # NOTE: tokenizers>=0.23 rejects local paths in from_pretrained (treats them as
    # HF repo ids) — load the JSON directly instead.
    with open(TOKENIZER_FILE, encoding="utf-8") as f:
        tok = Tokenizer.from_str(f.read())
    tok.enable_truncation(max_length=MAX_TOKENS)
    pad_id = tok.token_to_id(QWEN3_PAD_TOKEN)
    if pad_id is None:  # defensive fallback
        pad_id = 0
    # Right padding: real tokens stay at the start of every row, so under causal
    # attention pads never influence real-token hidden states. We then pool at
    # each row's last real token (see _pool_last_real_token). The graph also takes
    # an attention_mask input, which excludes pads from attention entirely.
    tok.enable_padding(direction="right", pad_id=pad_id, pad_token=QWEN3_PAD_TOKEN)
    return tok, pad_id


if os.environ.get("EMBED_SKIP_MODEL_LOAD") == "1":
    # Unit-test mode: do not load the 614 MB model. Tests monkeypatch
    # `session`, `tokenizer` and `_run_batch` as needed.
    session = None  # type: ignore[assignment]
    tokenizer = None  # type: ignore[assignment]
    INPUTS = {}
    OUTPUTS = []
    EMBED_DIM = 1024
    _PAD_ID = 0
else:
    session = _build_session()
    tokenizer, _PAD_ID = _build_tokenizer()

    INPUTS = {i.name: i for i in session.get_inputs()}
    OUTPUTS = [o.name for o in session.get_outputs()]
    log.info("ONNX inputs: %s", sorted(INPUTS.keys()))
    log.info("ONNX outputs: %s", OUTPUTS)

    # Determine the embedding dimension from the last_hidden_state output shape.
    _dim = None
    for o in session.get_outputs():
        shape = o.shape  # e.g. ['batch_size', 'sequence_length', 1024] or with -1 for dynamic
        if isinstance(shape, list) and len(shape) == 3:
            for s in reversed(shape):
                if isinstance(s, int) and s > 1:
                    _dim = s
                    break
        if _dim:
            break
    EMBED_DIM = _dim or 1024

# Serialize concurrent inferences (single ORT session, bounded RAM).
_infer_lock = threading.Lock()


def _empty_past_shape(name: str, batch: int) -> list[int]:
    """Shape for an empty past_key_values tensor: declared dims with the
    sequence-length dim forced to 0."""
    shape = INPUTS[name].shape
    out: list[int] = []
    dynamic_dims = 0
    for i, s in enumerate(shape):
        if isinstance(s, int) and s > 0:
            out.append(s)
        else:
            dynamic_dims += 1
            # Convention for these exports: [batch, num_heads, past_seq_len, head_dim]
            # (or [batch, past_seq_len, ...]). The first dynamic dim is batch, the
            # second is the past sequence length — force it to 0.
            if dynamic_dims == 1:
                out.append(batch)
            else:
                out.append(0)
    return out


def _run_batch(texts: list[str]) -> np.ndarray:
    """Embed a batch (len <= MAX_BATCH). Returns float32 array [B, EMBED_DIM]."""
    enc = tokenizer.encode_batch(texts)
    input_ids = np.array([e.ids for e in enc], dtype=np.int64)
    bsz, seq_len = input_ids.shape

    feeds: dict[str, np.ndarray] = {}
    for name, meta in INPUTS.items():
        if name == "input_ids":
            feeds[name] = input_ids
        elif name == "attention_mask":
            mask = (input_ids != _PAD_ID).astype(np.int64)
            feeds[name] = mask
        elif name == "position_ids":
            feeds[name] = np.tile(np.arange(seq_len, dtype=np.int64), (bsz, 1))
        elif name.startswith("past_key_values"):
            feeds[name] = np.zeros(_empty_past_shape(name, bsz), dtype=np.float32)
        else:
            raise RuntimeError(f"Unsupported ONNX input: {name}")

    with _infer_lock:
        # Request only last_hidden_state — skip materializing the 56 present.*
        # KV-cache outputs we never use.
        (hidden,) = session.run(["last_hidden_state"], feeds)

    return _pool_last_real_token(hidden, input_ids)


def _pool_last_real_token(hidden: np.ndarray, input_ids: np.ndarray) -> np.ndarray:
    """Gather each row's last real (non-pad) token and L2-normalize."""
    non_pad = (input_ids != _PAD_ID).sum(axis=1)  # [B] count of real tokens
    last_idx = np.clip(non_pad - 1, 0, hidden.shape[1] - 1)
    rows = np.arange(hidden.shape[0])
    vecs = hidden[rows, last_idx, :]
    norms = np.linalg.norm(vecs, axis=1, keepdims=True)
    norms[norms == 0] = 1.0
    return (vecs / norms).astype(np.float32)


def embed_texts(texts: list[str], kind: str) -> tuple[list[list[float]], bool]:
    """Embed texts with the given type ('document' | 'query').

    Returns (vectors, truncated_anywhere).
    """
    if kind == "query":
        prepared = [QUERY_INSTRUCT + t for t in texts]
    else:
        prepared = list(texts)

    vectors: list[np.ndarray] = []
    truncated = False
    for i in range(0, len(prepared), MAX_BATCH):
        chunk = prepared[i:i + MAX_BATCH]
        if tokenizer is not None:  # None only in unit-test mode
            enc = tokenizer.encode_batch(chunk)
            if any(len(e.ids) >= MAX_TOKENS for e in enc):
                truncated = True
        vectors.append(_run_batch(chunk))

    return [v.tolist() for v in np.concatenate(vectors, axis=0)], truncated


# ---------------------------------------------------------------------------
# FastAPI app
# ---------------------------------------------------------------------------

app = FastAPI(title="wiki4ai-embedding", version="1.0.0")


@app.get("/health")
def health():
    return {
        "status": "ok",
        "model": MODEL_NAME,
        "dim": EMBED_DIM,
        "max_batch": MAX_BATCH,
        "max_tokens": MAX_TOKENS,
    }


@app.post("/embed", response_model=EmbedResponse)
def embed(req: EmbedRequest):
    try:
        vectors, truncated = embed_texts(req.texts, req.type)
    except Exception as exc:  # noqa: BLE001 — report a clean 500 to the client
        log.exception("embedding failed")
        raise HTTPException(status_code=500, detail=f"embedding failed: {exc}") from exc
    if len(vectors) != len(req.texts):
        raise HTTPException(status_code=500, detail="internal error: vector count mismatch")
    return EmbedResponse(vectors=vectors, dim=EMBED_DIM, model=MODEL_NAME, truncated=truncated)
