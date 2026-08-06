"""
Tests for Wiki4AI MCP Server vault_* tools.

The vault is end-to-end encrypted: these tools encrypt/decrypt username,
password and notes locally (PBKDF2 + AES-256-GCM) using a key derived from
the master password + a salt fetched from the backend. These tests verify:
- The crypto helpers round-trip correctly and match the documented format
  (SHA-256 hex master-password preprocessing, shared IV, Base64 EncryptedField).
- Each tool calls the correct API endpoint(s) with the correct payload shape.
- Error paths (wrong master password, missing salt) raise clear MCPToolErrors.
- title/url/groupPath are never sent through decryption (they're plaintext).
"""

import base64
import hashlib
import json
import secrets
import sys
import os
from io import BytesIO
from unittest.mock import patch, MagicMock
from urllib.error import HTTPError

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..'))

from mcp_server import (
    vault_status,
    vault_list_entries,
    vault_search_entries,
    vault_get_entry,
    vault_create_entry,
    vault_update_entry,
    vault_delete_entry,
    _vault_password_hash,
    _vault_derive_key,
    _vault_encrypt_field,
    _vault_decrypt_field,
    _vault_get_key,
    create_mcp_server,
    MCPToolError,
    set_base_url,
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


MASTER_PASSWORD = "correct horse battery staple"
SALT_B64 = base64.b64encode(b"0123456789abcdef").decode()


# ─── Crypto helpers ────────────────────────────────────────────────────────

class TestVaultCrypto:
    def test_password_hash_matches_frontend_sha256_hex(self):
        # Frontend: hex(sha256(utf8(password))) - see cryptoApi.sha256() usage
        # in VaultUnlockScreen.tsx/VaultSetupScreen.tsx.
        expected = hashlib.sha256(MASTER_PASSWORD.encode("utf-8")).hexdigest()
        assert _vault_password_hash(MASTER_PASSWORD) == expected

    def test_derive_key_is_deterministic(self):
        key1 = _vault_derive_key("hash-value", SALT_B64)
        key2 = _vault_derive_key("hash-value", SALT_B64)
        assert key1 == key2
        assert len(key1) == 32  # AES-256

    def test_derive_key_differs_for_different_salt(self):
        other_salt = base64.b64encode(b"fedcba9876543210").decode()
        key1 = _vault_derive_key("hash-value", SALT_B64)
        key2 = _vault_derive_key("hash-value", other_salt)
        assert key1 != key2

    def test_encrypt_decrypt_field_round_trip(self):
        key = _vault_derive_key("hash-value", SALT_B64)
        iv = secrets.token_bytes(12)
        field = _vault_encrypt_field("my-secret-password", key, iv)

        assert isinstance(field["ciphertext"], str)
        assert isinstance(field["iv"], str)
        assert field["ciphertext"] != "my-secret-password"

        assert _vault_decrypt_field(field, key) == "my-secret-password"

    def test_decrypt_field_returns_none_for_missing_field(self):
        key = _vault_derive_key("hash-value", SALT_B64)
        assert _vault_decrypt_field(None, key) is None
        assert _vault_decrypt_field({}, key) is None

    def test_decrypt_field_returns_none_on_wrong_key(self):
        key1 = _vault_derive_key("hash-value-1", SALT_B64)
        key2 = _vault_derive_key("hash-value-2", SALT_B64)
        iv = secrets.token_bytes(12)
        field = _vault_encrypt_field("secret", key1, iv)
        assert _vault_decrypt_field(field, key2) is None


# ─── _vault_get_key (verify + salt fetch + derive) ─────────────────────────

class TestVaultGetKey:
    @patch("mcp_server.urlopen")
    def test_raises_clear_error_on_incorrect_master_password(self, mock_urlopen):
        mock_urlopen.side_effect = _http_error(401)

        with pytest.raises(MCPToolError) as exc_info:
            _vault_get_key(MASTER_PASSWORD)

        assert "Incorrect" in str(exc_info.value)

    @patch("mcp_server.urlopen")
    def test_raises_auth_error_not_password_error_when_jwt_missing(self, mock_urlopen):
        # Spring Security's entry point (no/invalid JWT never reaches the controller)
        # always returns a JSON body; the wrong-password 401 from
        # VaultController.verifyMasterPassword() never does. A missing-token 401
        # must NOT be reported as "Incorrect vault master password".
        mock_urlopen.side_effect = _http_error(401, {"message": "Authentication required"})

        with pytest.raises(MCPToolError) as exc_info:
            _vault_get_key(MASTER_PASSWORD)

        message = str(exc_info.value)
        assert "Incorrect" not in message
        assert "not authenticated" in message.lower()

    @patch("mcp_server.urlopen")
    def test_raises_auth_error_not_password_error_when_jwt_invalid(self, mock_urlopen):
        # Same as above but for the malformed/expired-token shape, which the
        # backend reports with a different body: {"error": "..."} instead of
        # {"message": "..."}.
        mock_urlopen.side_effect = _http_error(401, {"error": "Invalid or expired JWT token"})

        with pytest.raises(MCPToolError) as exc_info:
            _vault_get_key(MASTER_PASSWORD)

        message = str(exc_info.value)
        assert "Incorrect" not in message
        assert "not authenticated" in message.lower()

    @patch("mcp_server.urlopen")
    def test_raises_clear_error_when_salt_missing(self, mock_urlopen):
        mock_urlopen.side_effect = [
            _mock_response(None),  # verify - 200 OK
            _http_error(404),      # GET salt - not found
        ]

        with pytest.raises(MCPToolError) as exc_info:
            _vault_get_key(MASTER_PASSWORD)

        assert "salt" in str(exc_info.value).lower()

    @patch("mcp_server.urlopen")
    def test_derives_key_when_verify_and_salt_succeed(self, mock_urlopen):
        mock_urlopen.side_effect = [
            _mock_response(None),
            _mock_response({"salt": SALT_B64}),
        ]

        key = _vault_get_key(MASTER_PASSWORD)

        expected = _vault_derive_key(_vault_password_hash(MASTER_PASSWORD), SALT_B64)
        assert key == expected

    def test_requires_master_password(self):
        with pytest.raises(MCPToolError):
            _vault_get_key("")


# ─── vault_status ───────────────────────────────────────────────────────────

class TestVaultStatus:
    @patch("mcp_server.urlopen")
    def test_returns_true_when_set(self, mock_urlopen):
        mock_urlopen.return_value = _mock_response(True)
        assert vault_status() == {"hasMasterPasswordSet": True}

    @patch("mcp_server.urlopen")
    def test_returns_false_when_not_set(self, mock_urlopen):
        mock_urlopen.return_value = _mock_response(False)
        assert vault_status() == {"hasMasterPasswordSet": False}


# ─── vault_list_entries / vault_search_entries ─────────────────────────────

class TestVaultListAndSearchEntries:
    @patch("mcp_server.urlopen")
    def test_list_entries_returns_metadata_only_no_master_password(self, mock_urlopen):
        mock_urlopen.return_value = _mock_response([
            {
                "id": 1, "title": "GitHub", "url": "https://github.com", "groupPath": "/Work",
                "usernameEncrypted": {"ciphertext": "x", "iv": "y"},
                "passwordEncrypted": {"ciphertext": "x", "iv": "y"},
                "notesEncrypted": None,
                "createdAt": "2026-01-01T00:00:00", "updatedAt": "2026-01-01T00:00:00",
            }
        ])

        result = vault_list_entries()

        assert result == [{
            "id": 1, "title": "GitHub", "url": "https://github.com", "groupPath": "/Work",
            "createdAt": "2026-01-01T00:00:00", "updatedAt": "2026-01-01T00:00:00",
        }]
        assert "username" not in result[0]
        assert "password" not in result[0]
        # Only one HTTP call - no verify/salt round trip needed for metadata.
        assert mock_urlopen.call_count == 1

    @patch("mcp_server.urlopen")
    def test_search_entries_encodes_query_and_group_path(self, mock_urlopen):
        mock_urlopen.return_value = _mock_response([])

        vault_search_entries("hello world", group_path="/Work Stuff")

        request = mock_urlopen.call_args[0][0]
        url_str = str(request.full_url)
        assert "q=hello" in url_str
        assert "groupPath=" in url_str


# ─── vault_get_entry ─────────────────────────────────────────────────────────

class TestVaultGetEntry:
    @patch("mcp_server.urlopen")
    def test_returns_decrypted_entry(self, mock_urlopen):
        key = _vault_derive_key(_vault_password_hash(MASTER_PASSWORD), SALT_B64)
        iv = secrets.token_bytes(12)
        password_field = _vault_encrypt_field("hunter2", key, iv)
        username_field = _vault_encrypt_field("bob", key, iv)

        mock_urlopen.side_effect = [
            _mock_response(None),                  # verify
            _mock_response({"salt": SALT_B64}),    # salt
            _mock_response({                        # GET entry
                "id": 5, "title": "Test", "url": None, "groupPath": None,
                "usernameEncrypted": username_field,
                "passwordEncrypted": password_field,
                "notesEncrypted": None,
                "createdAt": "2026-01-01T00:00:00", "updatedAt": "2026-01-01T00:00:00",
            }),
        ]

        result = vault_get_entry(5, MASTER_PASSWORD)

        assert result["username"] == "bob"
        assert result["password"] == "hunter2"
        assert result["notes"] is None
        assert result["id"] == 5


# ─── vault_create_entry ──────────────────────────────────────────────────────

class TestVaultCreateEntry:
    @patch("mcp_server.urlopen")
    def test_sends_encrypted_field_shape_with_shared_iv(self, mock_urlopen):
        mock_urlopen.side_effect = [
            _mock_response(None),
            _mock_response({"salt": SALT_B64}),
            _mock_response({
                "id": 9, "title": "GitHub", "url": "https://github.com", "groupPath": None,
                "createdAt": "2026-01-01T00:00:00", "updatedAt": "2026-01-01T00:00:00",
            }),
        ]

        result = vault_create_entry(
            "GitHub", "s3cr3t!", MASTER_PASSWORD, username="me@example.com", url="https://github.com",
        )

        assert result["id"] == 9
        assert "password" not in result  # plaintext never echoed back

        create_call = mock_urlopen.call_args_list[2]
        request = create_call[0][0]
        body = json.loads(request.data.decode("utf-8"))

        assert body["title"] == "GitHub"
        assert body["url"] == "https://github.com"
        assert isinstance(body["passwordEncrypted"], dict)
        assert isinstance(body["usernameEncrypted"], dict)
        assert "notesEncrypted" not in body
        # Single IV shared across fields - the backend only persists one IV per entry.
        assert body["passwordEncrypted"]["iv"] == body["usernameEncrypted"]["iv"]

    @patch("mcp_server.urlopen")
    def test_omits_optional_fields_when_not_provided(self, mock_urlopen):
        mock_urlopen.side_effect = [
            _mock_response(None),
            _mock_response({"salt": SALT_B64}),
            _mock_response({"id": 1, "title": "Minimal", "url": None, "groupPath": None,
                             "createdAt": "2026-01-01T00:00:00", "updatedAt": "2026-01-01T00:00:00"}),
        ]

        vault_create_entry("Minimal", "pw", MASTER_PASSWORD)

        create_call = mock_urlopen.call_args_list[2]
        body = json.loads(create_call[0][0].data.decode("utf-8"))
        assert "usernameEncrypted" not in body
        assert "notesEncrypted" not in body


# ─── vault_update_entry ───────────────────────────────────────────────────────

class TestVaultUpdateEntry:
    @patch("mcp_server.urlopen")
    def test_preserves_untouched_fields(self, mock_urlopen):
        key = _vault_derive_key(_vault_password_hash(MASTER_PASSWORD), SALT_B64)
        iv = secrets.token_bytes(12)
        existing_password = _vault_encrypt_field("old-password", key, iv)
        existing_username = _vault_encrypt_field("alice", key, iv)

        mock_urlopen.side_effect = [
            _mock_response(None),                # verify
            _mock_response({"salt": SALT_B64}),  # salt
            _mock_response({                      # GET current entry
                "id": 3, "title": "Existing", "url": "https://old.example.com", "groupPath": "/Old",
                "usernameEncrypted": existing_username, "passwordEncrypted": existing_password,
                "notesEncrypted": None,
                "createdAt": "2026-01-01T00:00:00", "updatedAt": "2026-01-01T00:00:00",
            }),
            _mock_response({                      # PUT response
                "id": 3, "title": "Existing", "url": "https://old.example.com", "groupPath": "/Old",
                "createdAt": "2026-01-01T00:00:00", "updatedAt": "2026-01-02T00:00:00",
            }),
        ]

        vault_update_entry(3, MASTER_PASSWORD, password="new-password")

        put_call = mock_urlopen.call_args_list[3]
        request = put_call[0][0]
        assert request.get_method() == "PUT"
        body = json.loads(request.data.decode("utf-8"))
        assert body["title"] == "Existing"  # preserved
        assert body["url"] == "https://old.example.com"  # preserved

        assert _vault_decrypt_field(body["passwordEncrypted"], key) == "new-password"
        assert _vault_decrypt_field(body["usernameEncrypted"], key) == "alice"  # preserved, re-encrypted

    @patch("mcp_server.urlopen")
    def test_rejects_clearing_password_to_empty(self, mock_urlopen):
        key = _vault_derive_key(_vault_password_hash(MASTER_PASSWORD), SALT_B64)
        iv = secrets.token_bytes(12)
        # Simulate an entry whose stored password can't be recovered (edge case).
        mock_urlopen.side_effect = [
            _mock_response(None),
            _mock_response({"salt": SALT_B64}),
            _mock_response({
                "id": 3, "title": "Existing", "url": None, "groupPath": None,
                "usernameEncrypted": None, "passwordEncrypted": _vault_encrypt_field("", key, iv),
                "notesEncrypted": None,
                "createdAt": "2026-01-01T00:00:00", "updatedAt": "2026-01-01T00:00:00",
            }),
        ]

        with pytest.raises(MCPToolError):
            vault_update_entry(3, MASTER_PASSWORD, title="New Title")


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
