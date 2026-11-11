"""
Tests for the vault v2 primitives in mcp_server (WIKI4AI-197 D2b).

These are the pieces the MCP server shares with the browser: the plaintext
encoding (JSON.stringify, not json.dumps), the AAD layout, the KEK/DEK
hierarchy and the `iv || ciphertext || tag` field blob. The cross-language
proof that they agree with the frontend byte-for-byte lives in
test_vault_v2_golden.py, which reads the same fixture as
frontend/src/__tests__/vaultV2Golden.test.ts. This file covers the behaviour
around those primitives: what fails, what fails *loudly*, and what must never
round-trip into a different value.
"""

import base64
import hashlib
import json
import os
import re
import secrets
import sys

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from mcp_server import (
    MCPToolError,
    _vault_decrypt_v2_field,
    _vault_derive_kek,
    _vault_encrypt_v2_field,
    _vault_field_plaintext,
    _vault_field_text,
    _vault_fingerprint,
    _vault_json_string,
    _vault_new_entry_uuid,
    _vault_unwrap_dek,
    _vault_v2_aad,
    _vault_v2_parse_kdf,
    _vault_wrap_dek,
)

USER_ID = 7
ENTRY_UUID = "3f9a1c22-7777-4b0e-9a11-0d0d0d0d0d0d"
KEY_ID = "a" * 64
DEK = secrets.token_bytes(32)


# ─── the plaintext encoding: JSON.stringify, not json.dumps ──────────────────

class TestVaultJsonString:
    def test_plain_string_is_a_quoted_json_string(self):
        assert _vault_json_string("abc") == '"abc"'

    def test_non_ascii_is_left_as_raw_utf8_not_escaped(self):
        # json.dumps' default ensure_ascii=True would write "é" as \u00e9 —
        # the same characters, different bytes, and a row the browser reads
        # differently than the frontend wrote it.
        assert _vault_json_string("é") == '"é"'
        assert _vault_field_plaintext("é") == b'"\xc3\xa9"'
        assert _vault_field_plaintext("é") != json.dumps("é").encode("utf-8")

    def test_emoji_stays_one_raw_character(self):
        # A surrogate *pair* is a valid character, so it is encoded as 4 UTF-8
        # bytes exactly like TextEncoder does it.
        assert _vault_field_plaintext("😀") == b'"\xf0\x9f\x98\x80"'

    def test_short_escapes_match_json_stringify(self):
        assert _vault_json_string('a"b\\c') == '"a\\"b\\\\c"'
        assert _vault_json_string("\b\f\n\r\t") == '"\\b\\f\\n\\r\\t"'

    def test_other_control_characters_use_u00xx_lowercase(self):
        assert _vault_json_string("\x00\x1f") == '"\\u0000\\u001f"'

    def test_del_and_line_separators_are_not_escaped(self):
        # JSON.stringify leaves U+007F, U+2028 and U+2029 raw; escaping them
        # would change the bytes without changing the value.
        assert _vault_json_string("\x7f\u2028\u2029") == '"\x7f\u2028\u2029"'

    def test_lone_surrogate_is_escaped_not_dropped(self):
        # json.dumps(ensure_ascii=False) leaves the bare character, which UTF-8
        # cannot encode at all — the encode() call raises. Well-formed
        # JSON.stringify escapes exactly these halves of a broken pair.
        assert _vault_json_string("\ud800") == '"\\ud800"'
        assert _vault_field_plaintext("\ud800") == b'"\\ud800"'

    def test_mixed_diacritics_emoji_and_escapes_survive_utf8(self):
        value = 'Příšerně žluťoučký kůň — 😀 "quoted" \\slash\n tab'
        plaintext = _vault_field_plaintext(value)
        # Raw UTF-8 for the accented letters, and no \uXXXX escaping of them.
        assert "Příšerně".encode("utf-8") in plaintext
        assert b"\\u00e9" not in plaintext and b"\\u0159" not in plaintext
        assert _vault_field_text(plaintext) == value


class TestVaultFieldText:
    def test_json_string_becomes_the_string(self):
        assert _vault_field_text(b'"hunter2"') == "hunter2"

    def test_escaped_surrogate_becomes_the_lone_surrogate_again(self):
        assert _vault_field_text(b'"\\ud800"') == "\ud800"

    def test_non_json_bytes_are_returned_as_text_not_dropped(self):
        # Mirrors decodeFieldText's fallback: bytes written by something that
        # did not wrap them are shown, not lost.
        assert _vault_field_text(b"plain text") == "plain text"

    def test_json_that_is_not_a_string_is_returned_as_its_text(self):
        assert _vault_field_text(b"123") == "123"

    def test_invalid_utf8_is_replaced_not_raised(self):
        # TextDecoder(fatal: false) replaces ill-formed sequences with U+FFFD.
        # Raising here would turn an unreadable field into a crashed tool.
        assert _vault_field_text(b'"\xff\xfe"') == "\ufffd\ufffd"


# ─── the AAD ─────────────────────────────────────────────────────────────────

class TestVaultV2Aad:
    def test_layout_is_nul_separated_in_documented_order(self):
        aad = _vault_v2_aad(USER_ID, ENTRY_UUID, "password", KEY_ID)
        assert aad == "w4ai-vault\u00002\u00007\u0000{}\u0000password\u0000{}".format(
            ENTRY_UUID, KEY_ID
        ).encode("utf-8")

    def test_uuid_is_lowercased_like_the_frontend(self):
        assert _vault_v2_aad(USER_ID, ENTRY_UUID.upper(), "password", KEY_ID) == _vault_v2_aad(
            USER_ID, ENTRY_UUID, "password", KEY_ID
        )

    def test_every_component_changes_the_aad(self):
        base = _vault_v2_aad(USER_ID, ENTRY_UUID, "password", KEY_ID)
        assert _vault_v2_aad(USER_ID + 1, ENTRY_UUID, "password", KEY_ID) != base
        assert _vault_v2_aad(USER_ID, _vault_new_entry_uuid(), "password", KEY_ID) != base
        assert _vault_v2_aad(USER_ID, ENTRY_UUID, "notes", KEY_ID) != base
        assert _vault_v2_aad(USER_ID, ENTRY_UUID, "password", "b" * 64) != base


class TestVaultV2ParseKdf:
    def test_missing_or_unparseable_kdf_falls_back_to_the_default(self):
        assert _vault_v2_parse_kdf(None) == (600_000, 32)
        assert _vault_v2_parse_kdf("") == (600_000, 32)
        assert _vault_v2_parse_kdf("{not json") == (600_000, 32)
        assert _vault_v2_parse_kdf("[1,2]") == (600_000, 32)

    def test_server_params_win_over_the_default(self):
        kdf = '{"alg":"PBKDF2-HMAC-SHA256","iter":600000,"dkLen":256}'
        assert _vault_v2_parse_kdf(kdf) == (600_000, 32)
        assert _vault_v2_parse_kdf('{"iter":1000,"dkLen":128}') == (1000, 16)

    def test_nonsense_values_fall_back_individually(self):
        assert _vault_v2_parse_kdf('{"iter":0,"dkLen":-8}') == (600_000, 32)
        assert _vault_v2_parse_kdf('{"iter":"600000"}') == (600_000, 32)


# ─── the key hierarchy ───────────────────────────────────────────────────────

class TestVaultKeyHierarchy:
    def test_kek_is_pbkdf2_over_the_raw_password(self):
        salt_b64 = base64.b64encode(b"0123456789abcdef").decode()
        expected = hashlib.pbkdf2_hmac("sha256", b"correct horse", b"0123456789abcdef", 1000, 32)
        assert _vault_derive_kek("correct horse", salt_b64, 1000, 32) == expected

    def test_kek_is_not_the_v1_key_built_on_h(self):
        # Deriving v2 from h instead of the raw password produces a key the
        # server can recompute — the exact mistake the v2 hierarchy exists to
        # avoid, so it has to be a test and not a comment.
        salt_b64 = base64.b64encode(b"0123456789abcdef").decode()
        raw = "correct horse battery staple"
        h = hashlib.sha256(raw.encode("utf-8")).hexdigest()
        from_kek = _vault_derive_kek(raw, salt_b64, 1000, 32)
        from_h = _vault_derive_kek(h, salt_b64, 1000, 32)
        assert from_kek != from_h

    def test_kek_honours_iterations_and_key_length(self):
        salt_b64 = base64.b64encode(b"0123456789abcdef").decode()
        assert len(_vault_derive_kek("pw", salt_b64, 1000, 16)) == 16
        assert _vault_derive_kek("pw", salt_b64, 1001, 32) != _vault_derive_kek(
            "pw", salt_b64, 1000, 32
        )

    def test_fingerprint_is_sha256_hex(self):
        key = secrets.token_bytes(32)
        assert _vault_fingerprint(key) == hashlib.sha256(key).hexdigest()
        assert re.fullmatch(r"[0-9a-f]{64}", _vault_fingerprint(key))

    def test_wrap_unwrap_round_trip(self):
        kek = secrets.token_bytes(32)
        dek = secrets.token_bytes(32)
        wrapped = _vault_wrap_dek(kek, dek, iv=bytes(12))
        assert base64.b64decode(wrapped)[:12] == bytes(12)
        assert len(base64.b64decode(wrapped)) == 12 + 32 + 16
        assert _vault_unwrap_dek(kek, wrapped) == dek

    def test_unwrap_with_the_wrong_kek_returns_none(self):
        # This None *is* the master-password check: no verifier call, no h.
        wrapped = _vault_wrap_dek(secrets.token_bytes(32), DEK)
        assert _vault_unwrap_dek(secrets.token_bytes(32), wrapped) is None

    def test_unwrap_rejects_malformed_blobs(self):
        kek = secrets.token_bytes(32)
        assert _vault_unwrap_dek(kek, "") is None
        assert _vault_unwrap_dek(kek, "not base64 !!") is None
        assert _vault_unwrap_dek(kek, base64.b64encode(bytes(12)).decode()) is None

    def test_unwrap_rejects_a_key_of_the_wrong_length(self):
        kek = secrets.token_bytes(32)
        wrapped = _vault_wrap_dek(kek, secrets.token_bytes(16))
        assert _vault_unwrap_dek(kek, wrapped) is None


# ─── the field format ────────────────────────────────────────────────────────

class TestVaultV2Fields:
    def _encrypt(self, value, dek=DEK, iv=None, field="password", uuid=ENTRY_UUID, key_id=KEY_ID,
                 user_id=USER_ID):
        return _vault_encrypt_v2_field(
            value, dek, user_id, uuid, field, key_id, iv=iv
        )

    def _decrypt(self, packed, dek=DEK, field="password", uuid=ENTRY_UUID, key_id=KEY_ID,
                 user_id=USER_ID):
        return _vault_decrypt_v2_field(packed, dek, user_id, uuid, field, key_id)

    def test_round_trip(self):
        packed = self._encrypt("hunter2")
        assert self._decrypt(packed) == "hunter2"

    def test_blob_is_iv_then_ciphertext_then_tag_in_base64(self):
        iv = bytes(range(12))
        packed = self._encrypt("hunter2", iv=iv)
        raw = base64.b64decode(packed)
        assert raw[:12] == iv
        assert len(raw) == 12 + len(b'"hunter2"') + 16

    def test_each_field_gets_its_own_iv_by_default(self):
        a = self._encrypt("same", field="password")
        b = self._encrypt("same", field="notes")
        assert a != b  # a shared IV across fields was v1's nonce reuse

    def test_aad_mismatch_fails_the_tag(self):
        packed = self._encrypt("hunter2")
        assert self._decrypt(packed, field="username") is None
        assert self._decrypt(packed, uuid=_vault_new_entry_uuid()) is None
        assert self._decrypt(packed, key_id="b" * 64) is None
        assert self._decrypt(packed, user_id=USER_ID + 1) is None

    def test_wrong_dek_fails_the_tag(self):
        assert self._decrypt(self._encrypt("hunter2"), dek=secrets.token_bytes(32)) is None

    def test_absent_and_malformed_fields_decrypt_to_none(self):
        assert self._decrypt(None) is None
        assert self._decrypt("") is None
        assert self._decrypt("%%%") is None
        assert self._decrypt(base64.b64encode(bytes(4)).decode()) is None

    def test_diacritics_and_emoji_round_trip_exactly(self):
        for value in ["Příšerně žluťoučký kůň", "😀 — \ud800", "a\x00b", 'q"\\\n']:
            assert self._decrypt(self._encrypt(value)) == value


class TestVaultEntryUuid:
    def test_is_a_lowercase_uuid4(self):
        value = _vault_new_entry_uuid()
        assert re.fullmatch(r"[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}", value)

    def test_is_unique(self):
        assert _vault_new_entry_uuid() != _vault_new_entry_uuid()


class TestCryptoAvailability:
    def test_missing_cryptography_raises_a_clear_error(self, monkeypatch):
        import mcp_server

        monkeypatch.setattr(mcp_server, "AESGCM", None)
        with pytest.raises(MCPToolError) as exc_info:
            _vault_encrypt_v2_field("x", DEK, USER_ID, ENTRY_UUID, "password", KEY_ID)
        assert "cryptography" in str(exc_info.value)
