"""Unit tests for the embedding sidecar (WIKI4AI-34).

Covers: /health, /embed contract (dim, count), batch chunking (<= 4),
query instruction prefix, and last-real-token pooling + L2 normalization.
The ONNX session is stubbed via monkeypatch — no model download in CI.
"""

import numpy as np
import pytest

import app as app_module


# ---------------------------------------------------------------------------
# /health
# ---------------------------------------------------------------------------

def test_health(client):
    resp = client.get("/health")
    assert resp.status_code == 200
    body = resp.json()
    assert body["status"] == "ok"
    assert body["model"] == app_module.MODEL_NAME
    assert body["dim"] == 1024
    assert body["max_batch"] == app_module.MAX_BATCH
    assert body["max_tokens"] == app_module.MAX_TOKENS


# ---------------------------------------------------------------------------
# /embed contract
# ---------------------------------------------------------------------------

def _fake_run_batch(captured: list, dim: int = 8):
    def _run(texts):
        captured.append(list(texts))
        # Deterministic unit vectors (already L2-normalized).
        bsz = len(texts)
        vecs = np.zeros((bsz, dim), dtype=np.float32)
        for i in range(bsz):
            vecs[i, i % dim] = 1.0
        return vecs

    return _run


def test_embed_returns_dim_and_count(client, monkeypatch):
    captured: list = []
    monkeypatch.setattr(app_module, "_run_batch", _fake_run_batch(captured))

    resp = client.post("/embed", json={"texts": ["alpha", "beta", "gamma"], "type": "document"})
    assert resp.status_code == 200
    body = resp.json()
    assert len(body["vectors"]) == 3
    for v in body["vectors"]:
        assert len(v) == 8  # fake dim from the stubbed _run_batch
        norm = sum(x * x for x in v) ** 0.5
        assert abs(norm - 1.0) < 1e-6
    assert body["model"] == app_module.MODEL_NAME


def test_embed_batch_chunking_max_4(client, monkeypatch):
    captured: list = []
    monkeypatch.setattr(app_module, "_run_batch", _fake_run_batch(captured))

    texts = [f"t{i}" for i in range(9)]  # 9 -> chunks of 4 + 4 + 1
    resp = client.post("/embed", json={"texts": texts, "type": "document"})
    assert resp.status_code == 200
    assert len(captured) == 3
    assert [len(c) for c in captured] == [4, 4, 1]
    assert len(resp.json()["vectors"]) == 9


def test_embed_query_applies_instruction_prefix(client, monkeypatch):
    captured: list = []
    monkeypatch.setattr(app_module, "_run_batch", _fake_run_batch(captured))

    resp = client.post("/embed", json={"texts": ["how does login work"], "type": "query"})
    assert resp.status_code == 200
    sent = captured[0][0]
    assert sent.startswith("Instruct: ")
    assert sent.endswith("Query:how does login work")


def test_embed_document_has_no_prefix(client, monkeypatch):
    captured: list = []
    monkeypatch.setattr(app_module, "_run_batch", _fake_run_batch(captured))

    resp = client.post("/embed", json={"texts": ["plain document text"], "type": "document"})
    assert resp.status_code == 200
    assert captured[0][0] == "plain document text"


def test_embed_rejects_empty_texts(client):
    resp = client.post("/embed", json={"texts": [], "type": "document"})
    assert resp.status_code == 422


def test_embed_rejects_unknown_type(client, monkeypatch):
    captured: list = []
    monkeypatch.setattr(app_module, "_run_batch", _fake_run_batch(captured))
    resp = client.post("/embed", json={"texts": ["x"], "type": "bogus"})
    assert resp.status_code == 422


# ---------------------------------------------------------------------------
# Pooling math (pure function)
# ---------------------------------------------------------------------------

def _l2(v):
    n = float(np.linalg.norm(v))
    return v / (n if n > 0 else 1.0)


def test_pool_last_real_token_right_padding():
    # B=2, L=5, H=3. Row 0 has 3 real tokens, row 1 has 5 (right padding).
    hidden = np.zeros((2, 5, 3), dtype=np.float32)
    for i in range(2 * 5 * 3):
        hidden.flat[i] = float(i) + 1.0  # distinct values everywhere

    input_ids = np.array(
        [
            [10, 11, 12, 0, 0],   # 3 real tokens, pad id = 0
            [20, 21, 22, 23, 24],  # 5 real tokens
        ],
        dtype=np.int64,
    )

    out = app_module._pool_last_real_token(hidden, input_ids)

    assert out.shape == (2, 3)
    # Row 0: last real token at index 2 -> normalized hidden[0, 2, :]
    np.testing.assert_allclose(out[0], _l2(hidden[0, 2, :]), rtol=1e-5)
    # Row 1: last real token at index 4 -> normalized hidden[1, 4, :]
    np.testing.assert_allclose(out[1], _l2(hidden[1, 4, :]), rtol=1e-5)
    # L2 normalized
    for row in out:
        assert abs(float(np.linalg.norm(row)) - 1.0) < 1e-6


def test_pool_last_real_token_zero_vector_safe():
    hidden = np.zeros((1, 3, 4), dtype=np.float32)
    input_ids = np.array([[5, 0, 0]], dtype=np.int64)
    out = app_module._pool_last_real_token(hidden, input_ids)
    assert out.shape == (1, 4)
    # Zero vector stays zero (norm guard prevents division by zero).
    np.testing.assert_allclose(out[0], np.zeros(4))
