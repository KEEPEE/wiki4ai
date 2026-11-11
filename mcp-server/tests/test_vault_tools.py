"""
Tests for Wiki4AI MCP Server vault_* tools.

The vault is end-to-end encrypted: these tools encrypt/decrypt username,
password and notes HERE (PBKDF2 + AES-256-GCM) and the backend only ever sees
opaque blobs. Since WIKI4AI-197 D2b the tools read both crypto versions and
write v2, and they never send the master password or its hash to the backend.

What these tests hold the code to:

  * the shape of every request the tools make (endpoint, method, body),
  * that `h` and `masterPasswordHash` never leave this process,
  * that a row's `crypto_version` decides which key opens it,
  * that a field which cannot be opened refuses a write instead of clearing it
    (the data loss that made this ticket necessary),
  * that title/url/groupPath are never run through decryption.

Byte-for-byte agreement with the browser is a separate concern and lives in
test_vault_v2_golden.py + frontend/src/__tests__/vaultV2Golden.test.ts.
"""

import base64
import hashlib
import json
import re
import secrets
import sys
import os
from io import BytesIO
from unittest.mock import patch, MagicMock
from urllib.error import HTTPError

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..'))

from mcp_server import (
    MCPToolError,
    create_mcp_server,
    set_base_url,
    set_jwt_token,
    vault_create_entry,
    vault_delete_entry,
    vault_get_entry,
    vault_list_entries,
    vault_search_entries,
    vault_status,
    vault_update_entry,
    _vault_decrypt_field,
    _vault_decrypt_v2_field,
    _vault_derive_key,
    _vault_derive_kek,
    _vault_encrypt_field,
    _vault_encrypt_v2_field,
    _vault_fingerprint,
    _vault_get_context,
    _vault_password_hash,
    _vault_wrap_dek,
)


@pytest.fixture(autouse=True)
def reset_base_url():
    set_base_url("http://localhost:8080/api")
    yield


@pytest.fixture(autouse=True)
def reset_jwt_token_fixture():
    from mcp_server import set_jwt_token as _set
    _set(None)
    yield


def _mock_response(body):
    """Build a mock urlopen() context-manager response with a JSON body."""
    resp = MagicMock()
    resp.read.return_value = json.dumps(body).encode("utf-8") if body is not None else b""
    cm = MagicMock()
    cm.__enter__.return_value = resp
    return cm


def _http_error(code, body=None):
    return HTTPError(
        url="http://localhost:8080/api/v1/vault/x",
        code=code,
        msg="Error",
        hdrs={},
        fp=BytesIO(json.dumps(body or {}).encode("utf-8")),
    )


def _request_body(request):
    return json.loads(request.data.decode("utf-8")) if request.data else {}


def _methods(mock_urlopen):
    return [call[0][0].get_method() for call in mock_urlopen.call_args_list]


MASTER_PASSWORD = "correct horse battery staple"
SALT_B64 = base64.b64encode(b"0123456789abcdef").decode()
USER_ID = 7
KDF_JSON = '{"alg":"PBKDF2-HMAC-SHA256","iter":1000,"dkLen":256}'
FAST_ITERATIONS = 1_000  # a real vault uses 600k; the shape is identical

V1_KEY = _vault_derive_key(_vault_password_hash(MASTER_PASSWORD), SALT_B64)
DEK = secrets.token_bytes(32)
KEY_ID = _vault_fingerprint(DEK)


def _v2_params_body(initialized=True, salt=SALT_B64):
    if not initialized:
        return {"initialized": False, "salt": None, "kdf": None, "wrappedKey": None, "keyId": None}
    kek = _vault_derive_kek(MASTER_PASSWORD, salt, FAST_ITERATIONS, 32)
    return {
        "initialized": True,
        "salt": salt,
        "kdf": KDF_JSON,
        "wrappedKey": _vault_wrap_dek(kek, DEK),
        "keyId": KEY_ID,
    }


def _context_calls_v1_only(entries=None, salt=SALT_B64):
    """The responses _vault_get_context consumes when the vault has no v2 key."""
    return [
        _mock_response(_v2_params_body(initialized=False)),
        _mock_response({"id": USER_ID}),
        _mock_response({"salt": salt}) if salt else _http_error(404),
        _mock_response(entries if entries is not None else []),
    ]


def _context_calls_v2(salt=SALT_B64, v1_salt=SALT_B64):
    """The responses _vault_get_context consumes when the vault has a v2 key."""
    return [
        _mock_response(_v2_params_body()),
        _mock_response({"id": USER_ID}),
        _mock_response({"salt": v1_salt}) if v1_salt else _http_error(404),
    ]


def _v1_entry(entry_id=1, password="old-password", username="alice", notes=None,
              key=None, iv=None):
    """A backend-shaped v1 row."""
    key = V1_KEY if key is None else key
    iv = secrets.token_bytes(12) if iv is None else iv

    def seal(value, field_key):
        return _vault_encrypt_field(value, key, iv) if value else None

    return {
        "id": entry_id,
        "title": "Existing",
        "url": "https://old.example.com",
        "groupPath": "/Old",
        "cryptoVersion": 1,
        "entryUuid": "5a0b1c2d-3333-4a0b-9c1d-000000000001",
        "keyId": None,
        "usernameEncrypted": seal(username, "username"),
        "passwordEncrypted": seal(password, "password"),
        "notesEncrypted": seal(notes, "notes"),
        "usernameV2": None,
        "passwordV2": None,
        "notesV2": None,
        "createdAt": "2026-01-01T00:00:00",
        "updatedAt": "2026-01-01T00:00:00",
    }


def _v2_entry(entry_id=2, password="v2-password", username="bob", notes="keep me",
              dek=None, user_id=USER_ID, key_id=None, entry_uuid=None):
    """A backend-shaped v2 row, sealed with the same primitives the browser uses.

    The key id follows the DEK, because that is what the row claims: a row sealed
    under another key carries that key's fingerprint, and that is how a client
    knows not to re-seal it under its own.
    """
    dek = DEK if dek is None else dek
    key_id = key_id or _vault_fingerprint(dek)
    entry_uuid = entry_uuid or "5a0b1c2d-3333-4a0b-9c1d-000000000002"

    def seal(value, field):
        if value is None:
            return None
        return _vault_encrypt_v2_field(value, dek, user_id, entry_uuid, field, key_id)

    return {
        "id": entry_id,
        "title": "V2 Entry",
        "url": "https://v2.example.com",
        "groupPath": "/New",
        "cryptoVersion": 2,
        "entryUuid": entry_uuid,
        "keyId": key_id,
        "usernameEncrypted": None,
        "passwordEncrypted": None,
        "notesEncrypted": None,
        "usernameV2": seal(username, "username"),
        "passwordV2": seal(password, "password"),
        "notesV2": seal(notes, "notes"),
        "createdAt": "2026-01-01T00:00:00",
        "updatedAt": "2026-01-01T00:00:00",
    }


def _created_response(entry_id=9, crypto_version=2):
    return {
        "id": entry_id,
        "title": "GitHub",
        "url": "https://github.com",
        "groupPath": None,
        "cryptoVersion": crypto_version,
        "createdAt": "2026-01-01T00:00:00",
        "updatedAt": "2026-01-01T00:00:00",
    }


# ─── v1 crypto helpers (still needed to read rows that are v1) ───────────────

class TestVaultV1Crypto:
    def test_password_hash_matches_frontend_sha256_hex(self):
        expected = hashlib.sha256(MASTER_PASSWORD.encode("utf-8")).hexdigest()
        assert _vault_password_hash(MASTER_PASSWORD) == expected

    def test_derive_key_is_deterministic_and_salt_bound(self):
        other_salt = base64.b64encode(b"fedcba9876543210").decode()
        assert _vault_derive_key("hash-value", SALT_B64) == _vault_derive_key("hash-value", SALT_B64)
        assert len(_vault_derive_key("hash-value", SALT_B64)) == 32
        assert _vault_derive_key("hash-value", SALT_B64) != _vault_derive_key("hash-value", other_salt)

    def test_encrypt_decrypt_field_round_trip(self):
        iv = secrets.token_bytes(12)
        field = _vault_encrypt_field("my-secret-password", V1_KEY, iv)
        assert field["ciphertext"] != "my-secret-password"
        assert _vault_decrypt_field(field, V1_KEY) == "my-secret-password"

    def test_decrypt_field_returns_none_for_missing_field(self):
        assert _vault_decrypt_field(None, V1_KEY) is None
        assert _vault_decrypt_field({}, V1_KEY) is None

    def test_decrypt_field_returns_none_on_wrong_key(self):
        other = _vault_derive_key("other-hash", SALT_B64)
        field = _vault_encrypt_field("secret", V1_KEY, secrets.token_bytes(12))
        assert _vault_decrypt_field(field, other) is None


# ─── _vault_get_context: the keys, and no h on the wire ──────────────────────

class TestVaultGetContext:
    def test_requires_master_password(self):
        with pytest.raises(MCPToolError):
            _vault_get_context("")

    @patch("mcp_server.urlopen")
    def test_v2_vault_unwraps_the_dek_and_reports_the_key_id(self, mock_urlopen):
        mock_urlopen.side_effect = _context_calls_v2()
        ctx = _vault_get_context(MASTER_PASSWORD)
        assert ctx["v2"]["dek"] == DEK
        assert ctx["v2"]["key_id"] == KEY_ID
        assert ctx["user_id"] == USER_ID
        assert ctx["v1_key"] == V1_KEY

    @patch("mcp_server.urlopen")
    def test_h_is_never_sent_to_the_backend(self, mock_urlopen):
        """The whole point of D2b: no /verify, no masterPasswordHash, no h."""
        mock_urlopen.side_effect = _context_calls_v2() + [_mock_response(_v2_entry())]
        vault_get_entry(2, MASTER_PASSWORD)

        urls, bodies = [], []
        for call in mock_urlopen.call_args_list:
            request = call[0][0]
            urls.append(str(request.full_url))
            if request.data is not None:
                assert "masterPasswordHash" not in request.data.decode("utf-8")
                bodies.append(json.loads(request.data.decode("utf-8")))

        assert not any("master-password/verify" in url for url in urls), urls
        assert not any("master-password/set" in url for url in urls), urls
        # h must not appear anywhere on the wire, not even nested inside a body.
        wire = json.dumps({"urls": urls, "bodies": bodies})
        assert _vault_password_hash(MASTER_PASSWORD) not in wire
        assert MASTER_PASSWORD not in wire

    @patch("mcp_server.urlopen")
    def test_wrong_password_on_a_v2_vault_is_the_failed_unwrap(self, mock_urlopen):
        mock_urlopen.side_effect = _context_calls_v2()
        with pytest.raises(MCPToolError) as exc_info:
            _vault_get_context("definitely-not-the-password")
        assert "Incorrect" in str(exc_info.value)
        # No verifier fallback: the params and the profile, then the refusal.
        assert mock_urlopen.call_count == 2

    @patch("mcp_server.urlopen")
    def test_v1_only_vault_is_verified_by_opening_a_row(self, mock_urlopen):
        entry = _v1_entry(password="hunter2")
        mock_urlopen.side_effect = _context_calls_v1_only(entries=[entry])
        ctx = _vault_get_context(MASTER_PASSWORD)
        assert ctx["v2"] is None
        assert ctx["v1_key"] == V1_KEY

    @patch("mcp_server.urlopen")
    def test_v1_only_vault_with_wrong_password_raises(self, mock_urlopen):
        entry = _v1_entry(password="hunter2")
        mock_urlopen.side_effect = _context_calls_v1_only(entries=[entry])
        with pytest.raises(MCPToolError) as exc_info:
            _vault_get_context("wrong-password")
        assert "Incorrect vault master password" in str(exc_info.value)

    @patch("mcp_server.urlopen")
    def test_v1_only_vault_with_nothing_to_check_says_what_to_do(self, mock_urlopen):
        mock_urlopen.side_effect = _context_calls_v1_only(entries=[])
        with pytest.raises(MCPToolError) as exc_info:
            _vault_get_context(MASTER_PASSWORD)
        message = str(exc_info.value)
        assert "webui" in message and "v2" in message

    @patch("mcp_server.urlopen")
    def test_missing_v1_salt_is_not_fatal_once_the_vault_is_v2(self, mock_urlopen):
        """A fully migrated vault may legitimately have no usable v1 salt."""
        mock_urlopen.side_effect = _context_calls_v2(v1_salt=None)
        ctx = _vault_get_context(MASTER_PASSWORD)
        assert ctx["v1_key"] is None
        assert ctx["v2"]["dek"] == DEK

    @patch("mcp_server.urlopen")
    def test_kdf_iterations_come_from_the_server(self, mock_urlopen):
        """A server that raises the work factor must not strand this client."""
        kek = _vault_derive_kek(MASTER_PASSWORD, SALT_B64, 4_242, 32)
        mock_urlopen.side_effect = [
            _mock_response({
                "initialized": True, "salt": SALT_B64,
                "kdf": '{"alg":"PBKDF2-HMAC-SHA256","iter":4242,"dkLen":256}',
                "wrappedKey": _vault_wrap_dek(kek, DEK), "keyId": KEY_ID,
            }),
            _mock_response({"id": USER_ID}),
            _http_error(404),
        ]
        assert _vault_get_context(MASTER_PASSWORD)["v2"]["dek"] == DEK

    @patch("mcp_server.urlopen")
    def test_auth_layer_401_is_reported_as_a_login_problem(self, mock_urlopen):
        mock_urlopen.side_effect = _http_error(401, {"message": "Authentication required"})
        with pytest.raises(MCPToolError) as exc_info:
            _vault_get_context(MASTER_PASSWORD)
        message = str(exc_info.value)
        assert "not authenticated" in message.lower()
        assert "Incorrect" not in message

    @patch("mcp_server.urlopen")
    def test_auth_me_without_an_id_is_reported_clearly(self, mock_urlopen):
        mock_urlopen.side_effect = [
            _mock_response(_v2_params_body()),
            _mock_response({"username": "someone"}),
        ]
        with pytest.raises(MCPToolError) as exc_info:
            _vault_get_context(MASTER_PASSWORD)
        assert "auth/me" in str(exc_info.value)


# ─── vault_status ───────────────────────────────────────────────────────────

class TestVaultStatus:
    @patch("mcp_server.urlopen")
    def test_reports_both_the_verifier_and_the_crypto_version(self, mock_urlopen):
        mock_urlopen.side_effect = [
            _mock_response(True),
            _mock_response(_v2_params_body(initialized=True)),
        ]
        assert vault_status() == {"hasMasterPasswordSet": True, "v2Initialized": True}

    @patch("mcp_server.urlopen")
    def test_reports_a_vault_that_is_still_v1(self, mock_urlopen):
        mock_urlopen.side_effect = [
            _mock_response(True),
            _mock_response(_v2_params_body(initialized=False)),
        ]
        assert vault_status() == {"hasMasterPasswordSet": True, "v2Initialized": False}


# ─── vault_list_entries / vault_search_entries ─────────────────────────────

class TestVaultListAndSearchEntries:
    @patch("mcp_server.urlopen")
    def test_list_entries_returns_metadata_only_no_master_password(self, mock_urlopen):
        mock_urlopen.return_value = _mock_response([
            {
                "id": 1, "title": "GitHub", "url": "https://github.com", "groupPath": "/Work",
                "cryptoVersion": 2,
                "usernameEncrypted": {"ciphertext": "x", "iv": "y"},
                "passwordEncrypted": {"ciphertext": "x", "iv": "y"},
                "notesEncrypted": None,
                "createdAt": "2026-01-01T00:00:00", "updatedAt": "2026-01-01T00:00:00",
            }
        ])

        result = vault_list_entries()

        assert result == [{
            "id": 1, "title": "GitHub", "url": "https://github.com", "groupPath": "/Work",
            "cryptoVersion": 2,
            "createdAt": "2026-01-01T00:00:00", "updatedAt": "2026-01-01T00:00:00",
        }]
        assert "username" not in result[0]
        assert "password" not in result[0]
        # Only one HTTP call - no key derivation needed for metadata.
        assert mock_urlopen.call_count == 1

    @patch("mcp_server.urlopen")
    def test_a_row_without_the_column_counts_as_v1(self, mock_urlopen):
        mock_urlopen.return_value = _mock_response([
            {"id": 1, "title": "Old", "createdAt": "x", "updatedAt": "y"}
        ])
        assert vault_list_entries()[0]["cryptoVersion"] == 1

    @patch("mcp_server.urlopen")
    def test_search_entries_encodes_query_and_group_path(self, mock_urlopen):
        mock_urlopen.return_value = _mock_response([])
        vault_search_entries("hello world", group_path="/Work Stuff")
        request = mock_urlopen.call_args[0][0]
        url_str = str(request.full_url)
        assert "q=hello" in url_str
        assert "groupPath=" in url_str


# ─── vault_get_entry: reading both formats ──────────────────────────────────

class TestVaultGetEntry:
    @patch("mcp_server.urlopen")
    def test_reads_a_v1_row(self, mock_urlopen):
        entry = _v1_entry(entry_id=5, password="hunter2", username="bob", notes="poznámka é")
        mock_urlopen.side_effect = _context_calls_v1_only(entries=[entry]) + [
            _mock_response(entry),
        ]

        result = vault_get_entry(5, MASTER_PASSWORD)

        assert result["id"] == 5
        assert result["username"] == "bob"
        assert result["password"] == "hunter2"
        assert result["notes"] == "poznámka é"
        assert result["cryptoVersion"] == 1
        assert result["isReadable"] is True
        assert "warning" not in result

    @patch("mcp_server.urlopen")
    def test_reads_a_v2_row(self, mock_urlopen):
        entry = _v2_entry(entry_id=6, password="v2-heslo", username="bob", notes="😀 poznámka")
        mock_urlopen.side_effect = _context_calls_v2() + [_mock_response(entry)]

        result = vault_get_entry(6, MASTER_PASSWORD)

        assert result["password"] == "v2-heslo"
        assert result["username"] == "bob"
        assert result["notes"] == "😀 poznámka"
        assert result["cryptoVersion"] == 2
        assert result["entryUuid"] == entry["entryUuid"]
        assert result["isReadable"] is True

    @patch("mcp_server.urlopen")
    def test_a_v2_row_is_unreadable_without_a_v2_key_and_says_why(self, mock_urlopen):
        entry = _v2_entry()
        # The vault itself is still v1 (one readable v1 row to check the password
        # against), and this row is the v2 one it cannot open.
        mock_urlopen.side_effect = _context_calls_v1_only(entries=[_v1_entry()]) + [
            _mock_response(entry),
        ]

        result = vault_get_entry(entry["id"], MASTER_PASSWORD)

        assert result["isReadable"] is False
        assert result["password"] is None
        assert result["notes"] is None
        assert "v2" in result["warning"]

    @patch("mcp_server.urlopen")
    def test_a_v1_row_written_under_another_salt_is_reported_not_invented(self, mock_urlopen):
        other_key = _vault_derive_key(_vault_password_hash("some-other-master"), SALT_B64)
        entry = _v1_entry(password="hunter2", key=other_key)
        mock_urlopen.side_effect = _context_calls_v1_only(entries=[_v1_entry()]) + [
            _mock_response(entry),
        ]

        result = vault_get_entry(entry["id"], MASTER_PASSWORD)

        assert result["isReadable"] is False
        assert result["password"] is None
        assert "salt" in result["warning"]

    @patch("mcp_server.urlopen")
    def test_a_v2_row_sealed_under_another_key_is_unreadable(self, mock_urlopen):
        entry = _v2_entry(dek=secrets.token_bytes(32))
        mock_urlopen.side_effect = _context_calls_v2() + [_mock_response(entry)]
        result = vault_get_entry(entry["id"], MASTER_PASSWORD)
        assert result["isReadable"] is False

    @patch("mcp_server.urlopen")
    def test_optional_fields_stay_none_without_becoming_warnings(self, mock_urlopen):
        entry = _v2_entry(password="only-a-password", username=None, notes=None)
        mock_urlopen.side_effect = _context_calls_v2() + [_mock_response(entry)]
        result = vault_get_entry(entry["id"], MASTER_PASSWORD)
        assert result["isReadable"] is True
        assert result["username"] is None
        assert result["notes"] is None


# ─── vault_create_entry ──────────────────────────────────────────────────────

class TestVaultCreateEntry:
    @patch("mcp_server.urlopen")
    def test_writes_v2_with_its_own_iv_per_field(self, mock_urlopen):
        mock_urlopen.side_effect = _context_calls_v2() + [_mock_response(_created_response())]

        result = vault_create_entry(
            "GitHub", "s3cr3t!", MASTER_PASSWORD,
            username="me@example.com", notes="poznámka", url="https://github.com",
        )

        assert result["id"] == 9
        assert "password" not in result  # plaintext never echoed back

        body = _request_body(mock_urlopen.call_args_list[-1][0][0])
        assert body["title"] == "GitHub"
        assert body["url"] == "https://github.com"
        assert body["cryptoVersion"] == 2
        assert body["keyId"] == KEY_ID
        assert re.fullmatch(
            r"[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}",
            body["entryUuid"],
        )
        # No v1 columns on a v2 write.
        for legacy in ("usernameEncrypted", "passwordEncrypted", "notesEncrypted"):
            assert legacy not in body

        uuid = body["entryUuid"]
        for column, expected in (
            ("passwordV2", "s3cr3t!"),
            ("usernameV2", "me@example.com"),
            ("notesV2", "poznámka"),
        ):
            field = column[:-2]
            assert _vault_decrypt_v2_field(body[column], DEK, USER_ID, uuid, field, KEY_ID) == expected

        ivs = {base64.b64decode(body[c])[:12] for c in ("passwordV2", "usernameV2", "notesV2")}
        assert len(ivs) == 3  # v1 shared one IV per row; v2 must not

    @patch("mcp_server.urlopen")
    def test_omits_optional_fields_when_not_provided(self, mock_urlopen):
        mock_urlopen.side_effect = _context_calls_v2() + [_mock_response(_created_response())]
        vault_create_entry("Minimal", "pw", MASTER_PASSWORD)
        body = _request_body(mock_urlopen.call_args_list[-1][0][0])
        assert "usernameV2" not in body
        assert "notesV2" not in body
        assert "passwordV2" in body

    @patch("mcp_server.urlopen")
    def test_diacritics_and_emoji_reach_the_browser_unchanged(self, mock_urlopen):
        mock_urlopen.side_effect = _context_calls_v2() + [_mock_response(_created_response())]
        value = "Příšerně žluťoučký kůň — 😀 \ud800"
        vault_create_entry("Unicode", value, MASTER_PASSWORD, notes=value)
        body = _request_body(mock_urlopen.call_args_list[-1][0][0])
        uuid = body["entryUuid"]
        assert _vault_decrypt_v2_field(body["passwordV2"], DEK, USER_ID, uuid, "password", KEY_ID) == value
        assert _vault_decrypt_v2_field(body["notesV2"], DEK, USER_ID, uuid, "notes", KEY_ID) == value

    @patch("mcp_server.urlopen")
    def test_a_vault_without_a_v2_key_still_writes_v1(self, mock_urlopen):
        """Before the vault has a v2 key, v1 is the format its owner reads."""
        mock_urlopen.side_effect = _context_calls_v1_only(entries=[_v1_entry()]) + [
            _mock_response(_created_response(crypto_version=1)),
        ]
        result = vault_create_entry("Old", "pw", MASTER_PASSWORD, username="u")
        body = _request_body(mock_urlopen.call_args_list[-1][0][0])
        assert "cryptoVersion" not in body
        assert body["passwordEncrypted"]["iv"] == body["usernameEncrypted"]["iv"]
        assert _vault_decrypt_field(body["passwordEncrypted"], V1_KEY) == "pw"
        assert result["cryptoVersion"] == 1

    @patch("mcp_server.urlopen")
    def test_password_is_required(self, mock_urlopen):
        with pytest.raises(MCPToolError):
            vault_create_entry("x", "", MASTER_PASSWORD)
        assert mock_urlopen.call_count == 0


# ─── vault_update_entry: the no-silent-wipe rules ────────────────────────────

class TestVaultUpdateEntry:
    @patch("mcp_server.urlopen")
    def test_untouched_v2_fields_are_carried_over_byte_for_byte(self, mock_urlopen):
        entry = _v2_entry(password="old", username="alice", notes="poznámka, ktorá nesmie zmiznúť")
        mock_urlopen.side_effect = _context_calls_v2() + [
            _mock_response(entry),
            _mock_response({**entry, "updatedAt": "2026-01-02T00:00:00"}),
        ]

        vault_update_entry(entry["id"], MASTER_PASSWORD, password="new")

        body = _request_body(mock_urlopen.call_args_list[-1][0][0])
        assert body["cryptoVersion"] == 2
        assert body["keyId"] == KEY_ID
        # The row keeps the uuid its blobs are sealed to.
        assert body["entryUuid"] == entry["entryUuid"]
        # Untouched fields are the SAME bytes, not a re-encryption of a guess.
        assert body["usernameV2"] == entry["usernameV2"]
        assert body["notesV2"] == entry["notesV2"]
        assert _vault_decrypt_v2_field(
            body["passwordV2"], DEK, USER_ID, entry["entryUuid"], "password", KEY_ID
        ) == "new"

    @patch("mcp_server.urlopen")
    def test_title_and_url_are_preserved_when_not_given(self, mock_urlopen):
        entry = _v2_entry()
        mock_urlopen.side_effect = _context_calls_v2() + [
            _mock_response(entry), _mock_response(entry),
        ]
        vault_update_entry(entry["id"], MASTER_PASSWORD, password="new")
        body = _request_body(mock_urlopen.call_args_list[-1][0][0])
        assert body["title"] == entry["title"]
        assert body["url"] == entry["url"]
        assert body["groupPath"] == entry["groupPath"]

    @patch("mcp_server.urlopen")
    def test_a_v1_row_in_a_v2_vault_is_converted_and_merged(self, mock_urlopen):
        """The write the old code destroyed: v1 row, v2 vault, notes silently lost."""
        entry = _v1_entry(password="old-password", username="alice", notes="moja poznámka")
        mock_urlopen.side_effect = _context_calls_v2() + [
            _mock_response(entry),
            _mock_response({**entry, "cryptoVersion": 2}),
        ]

        vault_update_entry(entry["id"], MASTER_PASSWORD, password="new-password")

        body = _request_body(mock_urlopen.call_args_list[-1][0][0])
        assert body["cryptoVersion"] == 2
        assert body["keyId"] == KEY_ID
        assert body["entryUuid"] == entry["entryUuid"]
        uuid = body["entryUuid"]
        assert _vault_decrypt_v2_field(
            body["passwordV2"], DEK, USER_ID, uuid, "password", KEY_ID) == "new-password"
        assert _vault_decrypt_v2_field(
            body["usernameV2"], DEK, USER_ID, uuid, "username", KEY_ID) == "alice"
        assert _vault_decrypt_v2_field(
            body["notesV2"], DEK, USER_ID, uuid, "notes", KEY_ID) == "moja poznámka"

    @patch("mcp_server.urlopen")
    def test_an_unreadable_field_refuses_the_write_instead_of_clearing_it(self, mock_urlopen):
        """The data-loss fix, stated against the behaviour it replaced.

        Old behaviour: decrypt username and notes -> None -> omit them from the
        PUT -> the backend's full-snapshot update cleared both columns. The user
        changed a password and lost their notes.
        """
        other_key = _vault_derive_key(_vault_password_hash("another-master"), SALT_B64)
        entry = _v1_entry(password="old", username="alice", notes="poznanie", key=other_key)
        # The vault verifies against a row this key CAN open, so the context is
        # built and only this one row turns out to be unreadable.
        mock_urlopen.side_effect = _context_calls_v1_only(entries=[_v1_entry()]) + [
            _mock_response(entry),
        ]

        with pytest.raises(MCPToolError) as exc_info:
            vault_update_entry(entry["id"], MASTER_PASSWORD, password="new")

        message = str(exc_info.value)
        assert "notes" in message and "username" in message
        assert "Refusing to update" in message
        # The decisive part: no PUT ever left this process.
        assert "PUT" not in _methods(mock_urlopen)

    @patch("mcp_server.urlopen")
    def test_a_v2_row_under_a_foreign_key_is_refused_not_rewritten_as_v1(self, mock_urlopen):
        """Invariant I1: a v2 row never gets v1 bytes written over it."""
        entry = _v2_entry(dek=secrets.token_bytes(32))
        mock_urlopen.side_effect = _context_calls_v2() + [_mock_response(entry)]

        with pytest.raises(MCPToolError) as exc_info:
            vault_update_entry(entry["id"], MASTER_PASSWORD, password="new")

        assert "Refusing to update" in str(exc_info.value)
        assert "PUT" not in _methods(mock_urlopen)

    @patch("mcp_server.urlopen")
    def test_empty_string_clears_an_optional_field(self, mock_urlopen):
        entry = _v2_entry(username="alice", notes="notes")
        mock_urlopen.side_effect = _context_calls_v2() + [
            _mock_response(entry), _mock_response(entry),
        ]
        vault_update_entry(entry["id"], MASTER_PASSWORD, notes="")
        body = _request_body(mock_urlopen.call_args_list[-1][0][0])
        assert "notesV2" not in body
        assert body["usernameV2"] == entry["usernameV2"]

    @patch("mcp_server.urlopen")
    def test_a_row_with_no_password_to_preserve_is_refused(self, mock_urlopen):
        entry = _v2_entry(password=None, username="alice", notes=None)
        mock_urlopen.side_effect = _context_calls_v2() + [_mock_response(entry)]
        with pytest.raises(MCPToolError) as exc_info:
            vault_update_entry(entry["id"], MASTER_PASSWORD, title="New Title")
        assert "password" in str(exc_info.value)
        assert "PUT" not in _methods(mock_urlopen)

    @patch("mcp_server.urlopen")
    def test_v1_vault_update_still_merges_and_re_encrypts(self, mock_urlopen):
        entry = _v1_entry(password="old-password", username="alice", notes="poznamka")
        mock_urlopen.side_effect = _context_calls_v1_only(entries=[entry]) + [
            _mock_response(entry), _mock_response(entry),
        ]
        vault_update_entry(entry["id"], MASTER_PASSWORD, password="new-password")
        body = _request_body(mock_urlopen.call_args_list[-1][0][0])
        assert "cryptoVersion" not in body
        assert _vault_decrypt_field(body["passwordEncrypted"], V1_KEY) == "new-password"
        assert _vault_decrypt_field(body["usernameEncrypted"], V1_KEY) == "alice"
        assert _vault_decrypt_field(body["notesEncrypted"], V1_KEY) == "poznamka"


# ─── vault_delete_entry ───────────────────────────────────────────────────────

class TestVaultDeleteEntry:
    @patch("mcp_server.urlopen")
    def test_calls_delete_endpoint(self, mock_urlopen):
        mock_urlopen.return_value = _mock_response(None)
        result = vault_delete_entry(7)
        request = mock_urlopen.call_args[0][0]
        assert request.get_method() == "DELETE"
        assert "7" in str(request.full_url)
        assert "deleted" in result["message"]


# ─── Registration ────────────────────────────────────────────────────────────

class TestVaultToolsRegistration:
    def test_server_creates_with_vault_tools_without_error(self):
        mcp = create_mcp_server()
        assert mcp is not None
        assert mcp.name == "wiki4ai"
