# wiki4ai embedding sidecar — Qwen3-Embedding-0.6B int8 ONNX (WIKI4AI-34)
# Reproducible build: the model artifact is downloaded from HuggingFace at build time
# and baked into the image (~1 GB total). RAM budget: container cap 2.5 GB,
# batch <= 4, ORT enable_cpu_mem_arena=false (see research doc §9 / addendum).

FROM python:3.11-slim

ENV PYTHONUNBUFFERED=1 \
    OMP_NUM_THREADS=2 \
    MODEL_DIR=/models

WORKDIR /app

COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt

# Model artifact (Apache-2.0 base model): int8 ONNX weights + tokenizer files.
# https://huggingface.co/onnx-community/Qwen3-Embedding-0.6B-ONNX
# (python:3.11-slim ships without curl — install it for the download, keep it
#  out of the final image by cleaning apt lists.)
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /models && \
    curl -fL --retry 3 -o /models/model_int8.onnx \
      "https://huggingface.co/onnx-community/Qwen3-Embedding-0.6B-ONNX/resolve/main/onnx/model_int8.onnx" && \
    for f in tokenizer.json tokenizer_config.json vocab.json merges.txt added_tokens.json special_tokens_map.json config.json; do \
      curl -fL --retry 3 -o "/models/$f" \
        "https://huggingface.co/onnx-community/Qwen3-Embedding-0.6B-ONNX/resolve/main/$f"; \
    done && \
    ls -la /models

COPY app.py .

EXPOSE 8030

# First start loads the ~614 MB model into an ORT session (minutes on CPU);
# give it a long start period before failing the healthcheck.
HEALTHCHECK --interval=20s --timeout=10s --start-period=900s --retries=15 \
  CMD python -c "import urllib.request,sys; r=urllib.request.urlopen('http://127.0.0.1:8030/health', timeout=8); sys.exit(0 if r.status==200 else 1)"

CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8030"]
