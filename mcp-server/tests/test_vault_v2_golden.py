"""
The cross-language half of the vault v2 format proof (WIKI4AI-197 D2b).

tests/fixtures/vault_v2_golden.json holds fixed inputs — fixed keys, fixed
salts, fixed IVs — and the bytes they must produce. The same file is read by
frontend/src/__tests__/vaultV2Golden.test.ts. Two implementations in two
languages agreeing on every byte of a fixed vector is the only evidence that
counts here: a row written by MCP has to open in the browser, and the browser
does not care what this test believes.

Regenerate the fixture with tests/fixtures/gen_vault_v2_golden.py. If this
file fails without the fixture changing, the Python side drifted.
"""

import base64
import hashlib
import json
import os

import pytest

import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from mcp_server import (
    _vault_decrypt_v2_field,
    _vault_derive_kek,
    _vault_encrypt_v2_field,
    _vault_field_plaintext,
    _vault_v2_aad,
    _vault_v2_parse_kdf,
    _vault_unwrap_dek,
    _vault_wrap_dek,
)

FIXTURE_PATH = os.path.join(os.path.dirname(__file__), "fixtures", "vault_v2_golden.json")


@pytest.fixture(scope="module")
def golden() -> dict:
    with open(FIXTURE_PATH, "rb") as handle:
        raw = handle.read()
    # The fixture is pure ASCII on purpose: a lone surrogate is stored as an
    # escape, so both readers are handed the same character rather than two
    # different ideas of how to encode it.
    raw.decode("ascii")
    fixture = json.loads(raw)
    assert fixture["format"] == "w4ai-vault-v2-golden/1"
    return fixture


def test_sha256_vectors(golden):
    for vector in golden["sha256_hex"]:
        digest = hashlib.sha256(vector["input"].encode("utf-8")).hexdigest()
        assert digest == vector["hex"], vector["input"]


def test_pbkdf2_vectors(golden):
    for vector in golden["pbkdf2_sha256"]:
        key = _vault_derive_kek(
            vector["password"], vector["salt_b64"], vector["iterations"], 32
        )
        assert key.hex() == vector["key_hex"], vector["iterations"]


def test_json_text_vectors_are_the_exact_bytes(golden):
    for vector in golden["json_text"]:
        plaintext = _vault_field_plaintext(vector["value"])
        assert plaintext.hex() == vector["utf8_hex"], repr(vector["value"])


def test_aad_vectors(golden):
    for vector in golden["aad"]:
        aad = _vault_v2_aad(
            vector["user_id"], vector["entry_uuid"], vector["field"], vector["key_id"]
        )
        assert aad.hex() == vector["utf8_hex"], vector["field"]


def test_field_vectors_encrypt_to_the_pinned_blob(golden):
    for vector in golden["field"]:
        packed = _vault_encrypt_v2_field(
            vector["value"],
            bytes.fromhex(vector["dek_hex"]),
            vector["user_id"],
            vector["entry_uuid"],
            vector["field"],
            vector["key_id"],
            iv=bytes.fromhex(vector["iv_hex"]),
        )
        assert packed == vector["packed_b64"], vector["name"]


def test_field_vectors_decrypt_back_to_the_value(golden):
    for vector in golden["field"]:
        value = _vault_decrypt_v2_field(
            vector["packed_b64"],
            bytes.fromhex(vector["dek_hex"]),
            vector["user_id"],
            vector["entry_uuid"],
            vector["field"],
            vector["key_id"],
        )
        assert value == vector["value"], vector["name"]


def test_field_blob_layout_is_iv_then_ciphertext_then_tag(golden):
    for vector in golden["field"]:
        raw = base64.b64decode(vector["packed_b64"])
        iv = bytes.fromhex(vector["iv_hex"])
        assert raw[:12] == iv, vector["name"]
        # 12 IV + plaintext + 16 GCM tag.
        assert len(raw) == 12 + len(_vault_field_plaintext(vector["value"])) + 16, vector["name"]


def test_wrap_vectors_seal_and_open_the_dek(golden):
    for vector in golden["wrap"]:
        kek = bytes.fromhex(vector["kek_hex"])
        wrapped = _vault_wrap_dek(kek, bytes.fromhex(vector["dek_hex"]), iv=bytes.fromhex(vector["iv_hex"]))
        assert wrapped == vector["wrapped_b64"]
        assert _vault_unwrap_dek(kek, vector["wrapped_b64"]) == bytes.fromhex(vector["dek_hex"])


def test_kdf_params_match_the_server_default(golden):
    params = golden["kdf_params"]
    assert _vault_v2_parse_kdf(params["kdf_json"]) == (params["iterations"], params["key_bytes"])


def test_the_fixture_covers_what_it_claims_to(golden):
    # A fixture that quietly stops covering the hard cases would keep passing
    # while the format broke, so the coverage itself is asserted.
    values = [vector["value"] for vector in golden["json_text"]]
    assert any("é" in value or "š" in value for value in values)   # raw UTF-8
    assert any("😀" in value for value in values)               # 4-byte UTF-8
    assert any("\ud800" in value for value in values)          # lone surrogate
    assert any("\x00" in value for value in values)            # control escape
    assert "" in values                                            # empty string
    assert len(golden["field"]) >= 6
    assert {vector["field"] for vector in golden["field"]} == {"username", "password", "notes"}
