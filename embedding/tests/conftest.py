"""Pytest configuration for the embedding sidecar unit tests.

The real 614 MB ONNX model is NOT loaded in unit tests: EMBED_SKIP_MODEL_LOAD
must be set before `app` is imported (module-level load guard).
"""

import os

os.environ.setdefault("EMBED_SKIP_MODEL_LOAD", "1")

import pytest  # noqa: E402


@pytest.fixture()
def client():
    from fastapi.testclient import TestClient

    import app as app_module

    return TestClient(app_module.app)
