"""
Wiki4AI MCP Server
==================
MCP (Model Context Protocol) server that exposes Wiki4AI REST API as tools.

Usage:
  python mcp_server.py --base-url http://localhost:8080/api --token eyJhbGci...

Connect your AI agent to this server via stdio or SSE transport.

Authentication:
  - Endpoint access control (SSE mode): environment variable
    MCP_JWT_TOKEN=<token>. When set to a non-empty value, every request to
    the SSE endpoint must carry "Authorization: Bearer <token>" matching it;
    missing or wrong tokens are rejected with HTTP 401. When empty/unset,
    the endpoint is open (legacy behavior, no auth).
  - Backend identity JWT for Wiki4AI API calls:
      * Via client request header (SSE mode): Authorization: Bearer <token>
      * Via CLI argument: --token <jwt_token>

Priority order for backend identity JWT token:
  1. Client-provided token (via SSE request headers) — highest priority
  2. CLI argument --token
  3. No authentication (unauthenticated access)

This allows clients to dynamically provide their own JWT tokens without
requiring server-side configuration.

Note: MCP_JWT_TOKEN is an access credential for the MCP SSE endpoint, not a
backend identity token. It is deliberately NOT forwarded to the Wiki4AI
backend: the backend rejects any Bearer value that is not a valid
app-signed JWT with HTTP 401 — even on public endpoints.
"""

import argparse
import base64
import contextvars
import hmac
import os
import sys
from typing import Dict, List, Optional

try:
    from fastmcp import FastMCP
except ImportError:
    print("ERROR: fastmcp is required. Install with: pip install fastmcp")
    sys.exit(1)

# ─── MCP Instance (created at module level so @mcp.resource decorators work) ──

mcp = FastMCP("wiki4ai")


# ─── Configuration ────────────────────────────────────────────────────────────

DEFAULT_BASE_URL = "http://localhost:8080/api"

# Global base URL (set via CLI argument or environment variable)
BASE_URL: str = DEFAULT_BASE_URL

# Global JWT token (set via CLI argument, env var, or default to None for unauthenticated access)
JWT_TOKEN: Optional[str] = None

# Context variable for per-request JWT tokens from SSE clients
# This allows dynamic authentication without server-side configuration
jwt_token_context: contextvars.ContextVar[Optional[str]] = contextvars.ContextVar("jwt_token", default=None)


def set_base_url(url: str):
    """Set the backend API base URL."""
    global BASE_URL
    if not url.endswith("/"):
        url += "/"
    BASE_URL = url


def set_jwt_token(token: Optional[str]):
    """Set the JWT authentication token.

    Args:
        token: JWT Bearer token string, or None for unauthenticated access (e.g., health checks)
    """
    global JWT_TOKEN
    JWT_TOKEN = token


def get_current_jwt_token() -> Optional[str]:
    """Get the current JWT token for this request context.

    Priority order:
      1. Client-provided token from SSE request headers
      2. Server-side configured token (CLI arg or env var)

    Returns:
        The effective JWT token to use, or None if no authentication is available.
    """
    # Check client-provided token first (highest priority)
    client_token = jwt_token_context.get()
    if client_token:
        return client_token
    
    # Fall back to server-side configured token
    global JWT_TOKEN
    return JWT_TOKEN


# ─── HTTP Client (no external deps beyond stdlib) ──────────────────────────────

import json as _json
from urllib.request import Request, urlopen
from urllib.error import HTTPError, URLError


def _api_request(method: str, path: str, body: Optional[dict] = None) -> dict:
    """Make an HTTP request to the Wiki4AI backend API.

    Args:
        method: HTTP method (GET, POST, PUT, DELETE, etc.)
        path: API path (e.g., '/v1/projects')
        body: Optional JSON body for POST/PUT requests

    Returns:
        Parsed JSON response as a dict or list

    Raises:
        MCPToolError: On HTTP errors or connection failures
    """
    url = BASE_URL + path.lstrip("/")
    data = _json.dumps(body).encode("utf-8") if body else None
    headers = {"Content-Type": "application/json"}

    # Add JWT Bearer token for authentication (if configured)
    # Uses client-provided token from SSE request headers with highest priority
    current_token = get_current_jwt_token()
    if current_token:
        headers["Authorization"] = f"Bearer {current_token}"

    req = Request(url, data=data, headers=headers, method=method)

    try:
        with urlopen(req, timeout=30) as resp:
            content = resp.read().decode("utf-8")
            return _json.loads(content) if content else {}
    except HTTPError as e:
        error_body = e.read().decode("utf-8", errors="replace")
        raise MCPToolError(
            f"API Error {e.code}: {error_body}",
            status_code=e.code,
            details=_json.loads(error_body) if error_body else None,
        )
    except URLError as e:
        raise MCPToolError(f"Connection failed to {url}: {e.reason}")


def _api_request_multipart(
    path: str,
    field_name: str,
    filename: str,
    content_type: str,
    data: bytes,
) -> dict:
    """Make a multipart/form-data HTTP request to the Wiki4AI backend API.

    Used by upload_image (WIKI4AI-64) to send binary image payloads — the JSON
    _api_request helper cannot carry raw bytes. Authentication works exactly
    like _api_request: the effective JWT (X-Wiki4AI-JWT identity channel on
    gated instances, Authorization header on open instances, or a server-side
    --token) is attached as a Bearer token.

    Args:
        path: API path (e.g., '/v1/images/my-project')
        field_name: multipart form field name (the backend expects "file")
        filename: original filename for the Content-Disposition header
        content_type: MIME type of the file part (backend re-detects from magic bytes)
        data: raw file bytes

    Returns:
        Parsed JSON response as a dict

    Raises:
        MCPToolError: On HTTP errors or connection failures
    """
    import uuid as _uuid

    boundary = "----Wiki4AIMultipart" + _uuid.uuid4().hex
    safe_filename = filename.replace("\r", "").replace("\n", "") or "upload.bin"

    body = bytearray()
    body += f"--{boundary}\r\n".encode("ascii")
    body += (
        f'Content-Disposition: form-data; name="{field_name}"; filename="{safe_filename}"\r\n'
    ).encode("utf-8")
    body += f"Content-Type: {content_type}\r\n".encode("utf-8")
    body += b"\r\n"
    body += data
    body += f"\r\n--{boundary}--\r\n".encode("ascii")

    url = BASE_URL + path.lstrip("/")
    headers = {"Content-Type": f"multipart/form-data; boundary={boundary}"}

    current_token = get_current_jwt_token()
    if current_token:
        headers["Authorization"] = f"Bearer {current_token}"

    req = Request(url, data=bytes(body), headers=headers, method="POST")

    try:
        with urlopen(req, timeout=60) as resp:
            content = resp.read().decode("utf-8")
            return _json.loads(content) if content else {}
    except HTTPError as e:
        error_body = e.read().decode("utf-8", errors="replace")
        raise MCPToolError(
            f"API Error {e.code}: {error_body}",
            status_code=e.code,
            details=_json.loads(error_body) if error_body else None,
        )
    except URLError as e:
        raise MCPToolError(f"Connection failed to {url}: {e.reason}")


class MCPToolError(Exception):
    """Custom exception for MCP tool errors."""

    def __init__(self, message: str, status_code: int = 0, details=None):
        self.message = message
        self.status_code = status_code
        self.details = details
        super().__init__(message)


# ─── SSE Transport with JWT Token Extraction ────────────────────────────────

try:
    from starlette.applications import Starlette
    from starlette.middleware import Middleware
    from starlette.requests import Request as StarletteRequest
    from starlette.routing import Route
    from starlette.responses import Response
    HAS_STARLETTE = True
except ImportError:
    HAS_STARLETTE = False


# ─── SSE Transport JWT Token Extraction ──────────────────────────────


def jwt_token_middleware(request: StarletteRequest, call_next):
    """Starlette-style middleware that extracts JWT token from SSE request headers.

    This middleware intercepts incoming HTTP requests and extracts the
    Authorization header to use as the JWT token for backend API calls.
    
    The token is stored in a context variable so it's available during
    tool execution without requiring server-side configuration.
    """
    # Extract JWT token from Authorization header
    auth_header = request.headers.get("Authorization", "")
    if auth_header.startswith("Bearer "):
        token = auth_header[7:].strip()  # Remove "Bearer " prefix
        token_var = jwt_token_context.set(token)
    
    try:
        return call_next(request)
    finally:
        # Reset the context variable after request completes
        if 'token_var' in locals():
            jwt_token_context.reset(token_var)


# ─── SSE Endpoint Access Control (Bearer token) ──────────────────────


def is_access_credential(token: Optional[str]) -> bool:
    """Return True if token equals the configured MCP endpoint access credential.

    The shared access credential (MCP_JWT_TOKEN) must never be forwarded to the
    Wiki4AI backend as an identity: it is not an app-signed JWT, and the
    backend's JwtAuthenticationFilter rejects any invalid Bearer value with
    HTTP 401 — even on public endpoints. When a client presents exactly the
    access credential, it authenticates against the MCP endpoint only; backend
    API calls fall back to the server-side configured identity (or anonymous).
    """
    if not token:
        return False
    expected = os.environ.get("MCP_JWT_TOKEN", "").strip()
    if not expected:
        return False
    return hmac.compare_digest(token.encode("utf-8"), expected.encode("utf-8"))


def select_jwt_token(
    auth_header: str,
    query_string: str,
    identity_header: Optional[str] = None,
) -> Optional[str]:
    """Select the backend identity JWT to forward for this request (WIKI4AI-61).

    Precedence:
      1. ``X-Wiki4AI-JWT`` header — dedicated identity channel. On gated instances
         (MCP_JWT_TOKEN set) the Authorization header must carry the shared access
         credential to pass BearerAuthMiddleware, so clients put their identity JWT
         here instead. Standard MCP clients forward client-level headers on every
         request, including POST /messages/ where tool calls are executed.
      2. ``Authorization: Bearer <token>`` header — used directly when it carries a
         real identity JWT (open instances without an access credential).
      3. ``?token=<token>`` query parameter — last-resort fallback for clients that
         cannot send custom headers.

    The shared access credential (MCP_JWT_TOKEN) is never returned: it
    authenticates against the MCP endpoint only and is not an app-signed JWT —
    the backend would reject it with HTTP 401, even on public endpoints.

    Args:
        auth_header: raw value of the Authorization request header (may be empty).
        query_string: raw query string of the request URL (may be empty).
        identity_header: raw value of the X-Wiki4AI-JWT request header (may be None).

    Returns:
        The identity JWT to forward to the Wiki4AI backend, or None (anonymous).
    """
    if identity_header and not is_access_credential(identity_header.strip()):
        return identity_header.strip()

    header_token = None
    if auth_header.startswith("Bearer "):
        header_token = auth_header[7:].strip()

    token_from_query = None
    if query_string and "token=" in query_string:
        for param in query_string.split("&"):
            if param.startswith("token="):
                token_from_query = param[6:]  # Remove "token=" prefix
                break

    if header_token and not is_access_credential(header_token):
        return header_token
    if token_from_query and not is_access_credential(token_from_query):
        return token_from_query
    return None


class BearerAuthMiddleware:
    """ASGI middleware enforcing a shared-secret Bearer token on the MCP SSE endpoint.

    When the MCP_JWT_TOKEN environment variable is set to a non-empty value,
    every HTTP request must carry an "Authorization: Bearer <token>" header
    whose token matches it (constant-time comparison). Missing or wrong
    tokens are rejected with HTTP 401 and a JSON error body.

    When MCP_JWT_TOKEN is empty or unset, all requests pass through
    unchanged (legacy open behavior) — this keeps instances that do not
    configure the token working exactly as before.

    The token is read from the environment on every request so tests can
    monkeypatch it and deployments can rotate it without code changes.

    Note: this middleware only gates access to the MCP endpoint. It does not
    (and must not) use the access token as an identity for backend API calls —
    the Wiki4AI backend rejects any Bearer value that is not a valid
    app-signed JWT with HTTP 401, even on public endpoints.
    """

    def __init__(self, app):
        self.app = app

    @staticmethod
    def _authorized(auth_header: str, expected: str) -> bool:
        """Return True if auth_header is 'Bearer <expected>' (constant-time compare)."""
        if not auth_header.startswith("Bearer "):
            return False
        provided = auth_header[7:].strip()
        return hmac.compare_digest(provided.encode("utf-8"), expected.encode("utf-8"))

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return

        expected = os.environ.get("MCP_JWT_TOKEN", "").strip()
        if not expected:
            # Auth disabled — legacy open behavior.
            await self.app(scope, receive, send)
            return

        auth_header = ""
        for key, value in scope.get("headers", []):
            if key == b"authorization":
                auth_header = value.decode("utf-8")
                break

        if not self._authorized(auth_header, expected):
            body = _json.dumps(
                {"error": "Unauthorized: missing or invalid Bearer token"}
            ).encode("utf-8")
            await send({
                "type": "http.response.start",
                "status": 401,
                "headers": [
                    (b"content-type", b"application/json"),
                    (b"content-length", str(len(body)).encode("utf-8")),
                ],
            })
            await send({"type": "http.response.body", "body": body})
            return

        await self.app(scope, receive, send)


# ─── Health Tools ─────────────────────────────────────────────────────────────

def health_check() -> dict:
    """Check if the Wiki4AI backend is healthy and running.

    Use this first to verify connectivity before making other API calls.
    This endpoint does not require authentication.

    Returns:
        Dict with status information (e.g., {"status": "UP"}).
    """
    return _api_request("GET", "/health")


# ─── Project Tools ────────────────────────────────────────────────────────────

def list_projects() -> list[dict]:
    """List all wiki projects accessible to the current user.

    Use this to discover available projects before working with documents.
    Each project has a unique `slug` that you use in other tools (e.g., list_documents, create_document).

    Returns:
        List of project objects. Each contains:
        - id (int): Internal numeric ID
        - name (str): Human-readable project name
        - slug (str): URL-friendly identifier used in all other API calls
        - description (str): Project description
        - documentCount (int): Number of documents in the project
        - createdAt (str): ISO 8601 creation timestamp
        - updatedAt (str): ISO 8601 last update timestamp

    Example:
        projects = list_projects()
        # Use projects[0]['slug'] to reference a project in other tools
    """
    return _api_request("GET", "/v1/projects")


def get_project(slug: str) -> dict:
    """Get detailed information about a specific project by its URL-friendly slug.

    Use this to verify a project exists and see its metadata (document count, description).

    Args:
        slug: The URL-friendly slug of the project (e.g., 'python', 'machine-learning').
            Get slugs from list_projects() or from URLs.

    Returns:
        Project object with id, name, slug, description, documentCount, createdAt, updatedAt.

    Example:
        get_project("my-wiki-project")  # Returns project details including document count
    """
    return _api_request("GET", f"/v1/projects/{slug}")


def create_project(name: str, description: Optional[str] = None) -> dict:
    """Create a new wiki project for organizing documents.

    The slug is auto-generated from the name (lowercase, spaces replaced with hyphens).
    After creation, use the returned `slug` to add documents to this project.

    Args:
        name: The name of the project (required, max 255 chars). A unique slug is auto-generated from this.
        description: An optional description of the project purpose (max 1000 chars).

    Returns:
        Created project object with id, name, slug, description, documentCount (0), createdAt, updatedAt.

    Example:
        create_project("My Documentation", description="Technical docs for my project")
        # Returns: {"id": 5, "name": "My Documentation", "slug": "my-documentation", ...}
    """
    body = {"name": name}
    if description:
        body["description"] = description
    return _api_request("POST", "/v1/projects", body)


def update_project(slug: str, name: Optional[str] = None, description: Optional[str] = None) -> dict:
    """Update an existing project by its slug. Partial update - only provided fields change.

    Args:
        slug: The URL-friendly slug of the project to update (required).
        name: New name for the project (optional, max 255 chars). Changing name also changes the slug.
        description: New description for the project (optional, max 1000 chars).

    Returns:
        Updated project object with id, name, slug, description, documentCount, createdAt, updatedAt.

    Example:
        update_project("my-wiki", description="Updated description")  # Only updates description
    """
    body = {}
    if name is not None:
        body["name"] = name
    if description is not None:
        body["description"] = description
    return _api_request("PUT", f"/v1/projects/{slug}", body)


def delete_project(slug: str) -> dict:
    """Permanently delete a project and ALL its documents. This action is irreversible.

    WARNING: This deletes the project AND all documents within it. Use with caution.

    Args:
        slug: The URL-friendly slug of the project to delete (required).

    Returns:
        Confirmation message on success.

    Example:
        delete_project("old-project")  # Deletes project and all its documents permanently
    """
    _api_request("DELETE", f"/v1/projects/{slug}")
    return {"message": f"Project '{slug}' deleted successfully"}


# ─── Document Tools ───────────────────────────────────────────────────────────

def list_documents(project_slug: str, page: int = 0, size: int = 50) -> list[dict]:
    """List documents in a project (paginated). Does NOT include document content.

    Use this FIRST to discover all documents in a project before reading them.
    
    WORKFLOW — How to read ALL documents in a project:
        1. Call list_documents("servers") → returns list of document metadata (titles, slugs, IDs)
        2. For each document, call get_document(project_slug, doc['slug']) to read raw markdown content
           OR call get_document_content(project_slug, doc['slug']) for rendered HTML + extracted links
        3. Use the resource shortcuts below for direct access:
           - wiki://project-slug/doc-slug → returns raw markdown (via MCP Resource)
           - wiki://project-slug/doc-slug/html → returns rendered HTML (via MCP Resource)

    IMPORTANT: The returned list does NOT include the 'content' field. Each item contains only metadata.
    You MUST call get_document() or get_document_content() separately to read actual content.

    Args:
        project_slug: The URL-friendly slug of the project (required). Get from list_projects().
            Examples: "servers", "overview", "machine-learning"
        page: Page number, 0-indexed (default: 0). Use for pagination when there are many documents.
        size: Number of documents per page, max 100 (default: 50).

    Returns:
        List of document summaries. Each contains:
        - id (int): Internal numeric ID (used by add_link/remove_link)
        - title (str): Document title
        - slug (str): URL-friendly identifier for this document
        - projectId (int): Parent project ID
        - linkedDocuments (list[int]): IDs of linked documents
        - createdAt (str), updatedAt (str): ISO 8601 timestamps

    Example — Read all docs in "servers" project:
        # Step 1: List all documents
        docs = list_documents("servers")
        for doc in docs:
            print(f"{doc['title']} ({doc['slug']})")
        
        # Step 2: Read each document's content
        for doc in docs:
            markdown = get_document("servers", doc["slug"])["content"]
            html = get_document_content("servers", doc["slug"])["htmlContent"]

    Example — Single document read:
        list_documents("servers")       # → [{"id": 1, "title": "Overview", "slug": "overview", ...}]
        get_document("servers", "overview")   # → {"content": "# Overview\\n...", "title": "Overview", ...}
    """
    response = _api_request("GET", f"/v1/projects/{project_slug}/documents?page={page}&size={size}")
    # Backend returns a Spring Data Page object: {"content": [...], "totalElements": N, ...}
    documents = response.get("content", [])
    # Strip 'content' field from each document defensively (backend already excludes it)
    return [{k: v for k, v in doc.items() if k != "content"} for doc in documents]


def create_document(project_slug: str, title: str, content: Optional[str] = None) -> dict:
    """Create a new wiki document within a project.

    The slug is auto-generated from the title (lowercase, spaces to hyphens, diacritics transliterated).
    Supports standard Markdown and Mermaid diagrams in ` ```mermaid ` code blocks.

    Args:
        project_slug: The URL-friendly slug of the target project (required). Get from list_projects().
        title: The title of the document (required). A unique slug is auto-generated from this.
        content: Markdown content for the document (optional). Supports Mermaid diagrams in ` ```mermaid ` blocks.

    Returns:
        Created DocumentDTO with id, title, slug, projectId, createdAt, updatedAt.

    Example:
        create_document("my-project", "Getting Started", "# Welcome\n\nThis is the intro doc.")
        # Returns: {"id": 10, "title": "Getting Started", "slug": "getting-started", ...}

    For Mermaid diagrams, call get_mermaid_guide() first for syntax reference.
    """
    body = {"title": title}
    if content is not None:
        body["content"] = content
    return _api_request("POST", f"/v1/projects/{project_slug}/documents", body)


def batch_create_documents(project_slug: str, documents: list[dict]) -> list[dict]:
    """Create multiple wiki documents within a project in a single call.

    More efficient than calling create_document() repeatedly when creating many documents.
    Each document dict must have at minimum a 'title' key.

    Args:
        project_slug: The URL-friendly slug of the target project (required).
        documents: List of document dicts. Each MUST have:
            - title (str, required): The title of the document
            - content (str, optional): Markdown content for the document

    Returns:
        List of created DocumentDTO objects, one per input document.
        Each contains id, title, slug, projectId, createdAt, updatedAt.

    Example:
        batch_create_documents("my-project", [
            {"title": "Chapter 1", "content": "# Chapter 1\nContent here..."},
            {"title": "Chapter 2", "content": "# Chapter 2\nMore content..."},
            {"title": "Appendix"}  # content is optional
        ])
    """
    results = []
    for doc in documents:
        body = {"title": doc["title"]}
        if "content" in doc and doc["content"] is not None:
            body["content"] = doc["content"]
        created = _api_request("POST", f"/v1/projects/{project_slug}/documents", body)
        results.append(created)
    return results


def get_document(project_slug: str, doc_slug: str) -> dict:
    """Get a specific document by its slug within a project. INCLUDES full markdown content.

    Use this when you need to read the complete document including its raw markdown content.
    For rendered HTML with wiki links resolved, use get_document_content() instead.

    WORKFLOW — Reading all documents in a project:
        1. list_documents("servers") → returns [{"slug": "overview", "title": "Overview", ...}]
        2. For each doc, call get_document("servers", slug) to read raw markdown content
    
    Alternative workflows:
        - Use get_document_content() if you need rendered HTML + extracted wiki links
        - Use MCP Resources directly (wiki://project-slug/doc-slug) for raw markdown access

    Args:
        project_slug: The URL-friendly slug of the project (required). Get from list_projects().
            Examples: "servers", "overview", "machine-learning"
        doc_slug: The URL-friendly slug of the document (required). Get from list_documents().
            Examples: "overview", "getting-started", "api-reference"

    Returns:
        DocumentDTO with id, title, slug, content (full markdown), projectId, createdAt, updatedAt.

    Example — Read all docs in "servers":
        docs = list_documents("servers")
        for doc in docs:
            result = get_document("servers", doc["slug"])
            print(f"{doc['title']}: {result['content'][:100]}...")

    Example — Single document read:
        get_document("servers", "overview")  # → {"content": "# Overview\\n...", "title": "Overview", ...}
    """
    return _api_request("GET", f"/v1/projects/{project_slug}/documents/{doc_slug}")


def update_document(project_slug: str, doc_slug: str, title: Optional[str] = None, content: Optional[str] = None, edits: Optional[List[Dict]] = None) -> dict:
    """Update an existing document by its slug within a project.

    PARTIAL UPDATE: `title`, `content` and `edits` are independently optional —
    only the provided fields are updated, omitted fields remain unchanged.
    At least ONE of them MUST be provided; calling with none of them raises an
    error (400-like) before any HTTP request is made.

    Full replacement vs partial edits:
    - `content` -> FULL replacement of the document content (backward compatible).
    - `edits` -> incremental find/replace edits applied SEQUENTIALLY to the
      current content. Each edit is a dict:
          {"find": <exact old text>, "replace": <new text>, "replaceAll": <bool>}
      Semantics:
        - `find` is matched EXACTLY (case-sensitive, including whitespace).
        - Multiple edits are chained: edit N+1 sees the result of edit N.
        - `replace: ""` DELETES the matched text (a legitimate operation).
        - `replaceAll` defaults to false. When `find` occurs more than once and
          replaceAll is false, the server returns 400 (no random choice); you can
          inspect the `occurrences` count in the error body and retry.
        - When `find` is not found, the server returns 400 with `editIndex`
          (the failing edit, 0-based) and `occurrences: 0`.
    - `content` + `edits` in the same call are MUTUALLY EXCLUSIVE -> 400.
    - Allowed combinations: title-only, content-only, edits-only,
      title+content, title+edits.

    Note: when `title` is changed the slug is regenerated from the new title —
    the response contains the NEW slug, while the URL identifier in the request
    stays the original slug.

    Successful responses that used `edits` include `editsApplied: n` where n is
    the number of edits applied.

    Example usage:
        # Full content replacement (original behavior)
        update_document("my-project", "my-doc", content="# Updated Content\\n...")
        # Title-only update
        update_document("my-project", "my-doc", title="My Document Title")
        # Replace one paragraph (exact text, whitespace included)
        update_document("my-project", "my-doc", edits=[
            {"find": "Old paragraph text", "replace": "New paragraph text"}
        ])
        # Delete a paragraph (empty replace)
        update_document("my-project", "my-doc", edits=[
            {"find": "Deprecated section\\nold text", "replace": ""}
        ])
        # Replace ALL occurrences of a term
        update_document("my-project", "my-doc", edits=[
            {"find": "wiki4ai", "replace": "Wiki4AI", "replaceAll": True}
        ])

    Args:
        project_slug: The URL-friendly slug of the project (required).
        doc_slug: The URL-friendly slug of the document to update (required).
        title: New title for the document (optional). When changed, the slug is regenerated.
        content: New markdown content for the document (optional, FULL replacement).
            Supports Mermaid diagrams in ` ```mermaid ` blocks.
        edits: Optional list of find/replace edits for PARTIAL updates. Each item:
            {"find": str (required, exact match),
             "replace": str (required, "" deletes the matched text),
             "replaceAll": bool (optional, default false)}.

    Returns:
        Updated DocumentDTO with id, title, slug (the new one if the title changed),
        projectId, createdAt, updatedAt, and editsApplied (only when edits were used).

    Raises:
        MCPToolError: With status_code=400 when nothing is provided, or when both
            `content` and `edits` are given.
    """
    has_edits = edits is not None and len(edits) > 0
    if title is None and content is None and not has_edits:
        raise MCPToolError(
            "At least one of title, content or edits must be provided", status_code=400)
    if content is not None and has_edits:
        raise MCPToolError(
            "Cannot combine content (full replace) with edits (partial edits) in the same request",
            status_code=400)
    body = {}
    if title is not None:
        body["title"] = title
    if content is not None:
        body["content"] = content
    if has_edits:
        body["contentEdits"] = edits
    return _api_request("PUT", f"/v1/projects/{project_slug}/documents/{doc_slug}", body)


def delete_document(project_slug: str, doc_slug: str) -> dict:
    """Permanently delete a document from a project. This action is irreversible.

    WARNING: This deletes the document permanently. Any wiki links pointing to this document become broken.

    Args:
        project_slug: The URL-friendly slug of the project (required).
        doc_slug: The URL-friendly slug of the document to delete (required). Get from list_documents().

    Returns:
        Confirmation message on success.

    Example:
        delete_document("my-project", "outdated-document")  # Permanently removes the document
    """
    _api_request("DELETE", f"/v1/projects/{project_slug}/documents/{doc_slug}")
    return {"message": f"Document '{doc_slug}' deleted successfully"}


def get_document_content(project_slug: str, doc_slug: str) -> dict:
    """Get a document's content with rendered HTML and extracted wiki links.

    Use this when you need the rendered HTML version of a document (markdown converted to HTML).
    Also extracts [[WikiLink]] references and resolves linked documents.

    WORKFLOW — Reading all documents in a project:
        1. list_documents("servers") → get list of slugs
        2. For each doc, call get_document_content("servers", slug) for rendered HTML + links
    
    Alternative workflows:
        - Use get_document() instead if you need raw markdown content (not rendered HTML)
        - Use MCP Resources directly (wiki://slug/doc-slug or wiki://slug/doc-slug/html) 
          when your agent supports MCP Resources

    Difference from get_document(): This returns htmlContent (rendered HTML) instead of raw markdown.

    Args:
        project_slug: The URL-friendly slug of the project (required). Get from list_projects().
            Examples: "servers", "overview", "machine-learning"
        doc_slug: The URL-friendly slug of the document (required). Get from list_documents().
            Examples: "overview", "getting-started", "api-reference"

    Returns:
        Document content object with:
        - id (int): Document ID
        - title (str): Document title
        - htmlContent (str): Markdown rendered as HTML, with wiki links resolved as <a> tags
        - wikiLinks (list[str]): Extracted [[WikiLink]] titles found in the content
        - linkedDocuments (list[dict]): Full details of documents this document links to

    Example — Read all docs and extract their HTML + links:
        docs = list_documents("servers")
        for doc in docs:
            result = get_document_content("servers", doc["slug"])
            print(f"Title: {result['title']}")
            print(f"HTML length: {len(result['htmlContent'])}")
            print(f"Wiki links found: {result['wikiLinks']}")

    Example — Single document read:
        get_document_content("servers", "overview")  # → {"htmlContent": "<h1>Overview</h1>...", ...}
    """
    return _api_request("GET", f"/v1/projects/{project_slug}/documents/{doc_slug}/content")


# ─── Link Management Tools ────────────────────────────────────────────────────

def add_link(project_slug: str, doc_slug: str, target_document_id: int) -> dict:
    """Add a wiki link from one document to another within the same project.

    CRITICAL: target_document_id is an INTEGER ID (not the slug!). You MUST call list_documents() first
    to get the numeric id of the target document. Using the slug here will fail.

    Workflow:
        1. docs = list_documents(project_slug)  # Get all documents with their IDs
        2. target_id = next(d['id'] for d in docs if d['slug'] == 'target-doc-slug')
        3. add_link(project_slug, 'source-doc-slug', target_id)

    Args:
        project_slug: The URL-friendly slug of the project (required).
        doc_slug: The slug of the SOURCE document that will contain the link (required).
        target_document_id: The INTEGER ID of the TARGET document to link TO (required).
            NOT THE SLUG - use list_documents() first to find the correct numeric id.

    Returns:
        Updated source document with the new link included in linkedDocuments list.

    Example:
        # First, find the target document's integer ID
        docs = list_documents("my-project")
        target_id = [d['id'] for d in docs if d['slug'] == 'api-reference'][0]
        # Then add the link
        add_link("my-project", "getting-started", target_id)
    """
    body = {"targetDocumentId": target_document_id}
    return _api_request("POST", f"/v1/projects/{project_slug}/documents/{doc_slug}/links", body)


def remove_link(project_slug: str, doc_slug: str, target_document_id: int) -> dict:
    """Remove a wiki link between two documents within the same project.

    CRITICAL: target_document_id is an INTEGER ID (not the slug!). Same as add_link - use list_documents() to find IDs.

    Args:
        project_slug: The URL-friendly slug of the project (required).
        doc_slug: The slug of the SOURCE document that contains the link to remove (required).
        target_document_id: The INTEGER ID of the TARGET document to unlink from (required).
            NOT THE SLUG - use list_documents() first to find the correct numeric id.

    Returns:
        Confirmation message on success, or raises error if the link doesn't exist.

    Example:
        docs = list_documents("my-project")
        target_id = [d['id'] for d in docs if d['slug'] == 'old-reference'][0]
        remove_link("my-project", "getting-started", target_id)
    """
    _api_request("DELETE", f"/v1/projects/{project_slug}/documents/{doc_slug}/links/{target_document_id}")
    return {"message": f"Link removed: '{doc_slug}' → document {target_document_id}"}


def get_links(project_slug: str, doc_slug: str) -> list[dict]:
    """Get all documents that a specific document links to (outgoing links).

    Use this to see what other documents are referenced from a given document.

    Args:
        project_slug: The URL-friendly slug of the project (required).
        doc_slug: The slug of the source document to check (required). Get from list_documents().

    Returns:
        List of linked document objects with id, title, slug, and other metadata.
        Empty list if the document has no outgoing links.

    Example:
        links = get_links("my-project", "overview")  # See what docs 'overview' links to
    """
    return _api_request("GET", f"/v1/projects/{project_slug}/documents/{doc_slug}/links")


def get_backlinks(project_slug: str, doc_slug: str) -> list[dict]:
    """Get all documents that link TO a specific document (incoming/backlinks).

    Use this to find which documents reference a given document. Helpful for understanding
    documentation dependencies before deleting or restructuring documents.

    Args:
        project_slug: The URL-friendly slug of the project (required).
        doc_slug: The slug of the TARGET document to check backlinks for (required). Get from list_documents().

    Returns:
        List of document objects that have wiki links pointing to this document.
        Empty list if no documents link to this one.

    Example:
        backlinks = get_backlinks("my-project", "api-reference")  # Who links TO 'api-reference'?
    """
    return _api_request("GET", f"/v1/projects/{project_slug}/documents/{doc_slug}/backlinks")


# ─── Search Tools ─────────────────────────────────────────────────────────────

def search_documents(project_slug: str, keyword: str) -> list[dict]:
    """Search for documents within ONE project using HYBRID matching (text + semantic).

    The backend fuses two rankings with Reciprocal Rank Fusion (RRF):
      1. text match — the keyword appears literally in the document content, and
      2. semantic match — pgvector cosine similarity of the query embedding against
         each document's embedding (top-20). When the embedding sidecar is down the
         search degrades gracefully to text-only matching (no error).

    Use this when you know the project slug but not the exact document slug.
    The keyword must be at least 2 characters long.

    Args:
        project_slug: The URL-friendly slug of the project (required). Get from list_projects().
        keyword: Search term matched against document content, literally and semantically (min 2 chars). Whitespace is trimmed.

    Returns:
        List of matching document objects with id, title, slug, FULL content (not an excerpt),
        projectId, score (RRF relevance — higher is more relevant), createdAt, updatedAt.
        Empty list if no documents match the search term.

    Example:
        results = search_documents("my-project", "authentication")  # Find docs about authentication
        for doc in results:
            print(f"{doc['title']} (score {doc.get('score')}): {doc['content'][:120]}...")
    """
    from urllib.parse import quote_plus as _quote_plus
    encoded_keyword = _quote_plus(keyword.strip())
    return _api_request("GET", f"/v1/projects/{project_slug}/documents/search?keyword={encoded_keyword}")


def search_documents_global(keyword: str, limit: int = 20) -> list[dict]:
    """Search for documents across ALL projects using HYBRID matching (text + semantic).

    Global (cross-project) variant of search_documents: the backend fuses a literal
    text match with pgvector cosine similarity (RRF) over EVERY document in the wiki,
    not just one project. When the embedding sidecar is down the search degrades
    gracefully to text-only matching (no error).

    Authentication: this endpoint requires a valid Wiki4AI JWT (login-only policy).
    The MCP server forwards the client's identity JWT — from the X-Wiki4AI-JWT header
    on gated instances (where Authorization must carry the MCP access credential), or
    from the Authorization header on open instances; if no identity is available (and
    no server-side --token is configured), the backend returns 401 and this tool raises
    a clear "Not authenticated" error.

    Use search_documents_global when you don't know which project a document lives in;
    use search_documents when you already know the project slug. The keyword must be at
    least 2 characters long.

    Args:
        keyword: Search term matched against document content, literally and semantically (min 2 chars). Whitespace is trimmed.
        limit: Maximum number of results to return (default 20; the backend caps it at 50).

    Returns:
        List of matching hit objects with id, title, slug, projectSlug, projectName,
        score (RRF relevance — higher is more relevant), updatedAt and excerpt
        (~200 characters around the first keyword occurrence). Empty list if nothing matches.

    Example:
        hits = search_documents_global("push notifications")
        for hit in hits:
            print(f"[{hit['projectSlug']}] {hit['title']} (score {hit['score']:.4f})")
    """
    from urllib.parse import quote_plus as _quote_plus
    encoded_keyword = _quote_plus(keyword.strip())
    try:
        limit_value = int(limit)
    except (TypeError, ValueError):
        raise MCPToolError(f"Invalid limit: {limit!r} — must be an integer")
    try:
        return _api_request("GET", f"/v1/search/documents?keyword={encoded_keyword}&limit={limit_value}")
    except MCPToolError as e:
        if e.status_code == 401:
            raise MCPToolError(
                "Not authenticated — the global search endpoint requires a valid Wiki4AI JWT. "
                "Connect with your JWT in the X-Wiki4AI-JWT request header (gated instance, "
                "alongside the MCP access credential in Authorization) or in the Authorization "
                "header (open instance), or configure a server-side --token.",
                status_code=401,
            ) from e
        raise


# ─── Image Upload Tool (WIKI4AI-64) ─────────────────────────────────────────

MAX_IMAGE_UPLOAD_BYTES = 10 * 1024 * 1024  # must match the backend limit


def upload_image(project_slug: str, filename: str, image_base64: str) -> dict:
    """Upload an image to a Wiki4AI project and get a markdown snippet for it.

    Accepts a base64-encoded image (PNG/JPEG/WebP/GIF/SVG, max 10 MB), stores
    it in the wiki backend under an unguessable UUID name, and returns the URL
    plus a ready-to-paste markdown snippet `![alt](/images/{project}/{uuid}.ext)`.

    The returned markdown path is relative: the WebUI renders it by fetching the
    image through the authenticated API (login-only — uploaded images are NEVER
    anonymously accessible). External https image URLs in documents keep working
    as before; use this tool for screenshots and generated diagrams that should
    live inside the wiki.

    Authentication: requires a valid Wiki4AI JWT, forwarded from the client's
    X-Wiki4AI-JWT header (gated instance) or Authorization header (open
    instance); if none is available the backend returns 401 and this tool raises
    a clear "Not authenticated" error.

    Args:
        project_slug: The URL-friendly slug of the target project (required). Get from list_projects().
        filename: Original image filename, used for the markdown alt text (e.g., "screenshot.png").
        image_base64: Base64-encoded image bytes (standard base64; whitespace tolerated).

    Returns:
        Dict with url ("/images/{project}/{uuid}.ext"), markdown ("![alt](url)"),
        filename, storedName, size (bytes) and contentType. Paste `markdown`
        into a document via update_document / create_document.

    Example:
        import base64
        png = base64.b64encode(open("shot.png", "rb").read()).decode()
        result = upload_image("my-project", "shot.png", png)
        # -> {"url": "/images/my-project/9f1c...png", "markdown": "![shot](/images/my-project/9f1c...png)", ...}
    """
    import base64 as _base64

    if not project_slug or not project_slug.strip():
        raise MCPToolError("project_slug is required")
    if image_base64 is None or not str(image_base64).strip():
        raise MCPToolError("image_base64 is required (base64-encoded image bytes)")

    cleaned = "".join(str(image_base64).split())
    try:
        data = _base64.b64decode(cleaned, validate=True)
    except Exception as e:
        raise MCPToolError(f"Invalid base64 in image_base64: {e}") from e

    if not data:
        raise MCPToolError("image_base64 decodes to zero bytes")
    if len(data) > MAX_IMAGE_UPLOAD_BYTES:
        raise MCPToolError(
            f"Image is too large: {len(data)} bytes (limit {MAX_IMAGE_UPLOAD_BYTES} bytes / 10 MB)"
        )

    safe_filename = str(filename or "image").replace("\r", "").replace("\n", "")
    try:
        return _api_request_multipart(
            path=f"/v1/images/{project_slug.strip()}",
            field_name="file",
            filename=safe_filename,
            content_type="application/octet-stream",
            data=data,
        )
    except MCPToolError as e:
        if e.status_code == 401:
            raise MCPToolError(
                "Not authenticated — image upload requires a valid Wiki4AI JWT. "
                "Connect with your JWT in the X-Wiki4AI-JWT request header (gated instance, "
                "alongside the MCP access credential in Authorization) or in the Authorization "
                "header (open instance), or configure a server-side --token.",
                status_code=401,
            ) from e
        raise


# ─── Mermaid Guide Tool ──────────────────────────────────────────────────────

def get_mermaid_guide() -> str:
    """Get a complete Mermaid diagram guide for AI agents.

    Returns a comprehensive guide covering Mermaid syntax, 5 diagram types
    with working examples, tips for AI agents, and common error solutions.
    The guide is designed to be used directly by AI agents when creating
    documents that contain Mermaid diagrams.

    Returns:
        Complete Mermaid guide as a markdown-formatted string.
    """
    return """# Mermaid Diagram Guide for AI Agents

## Overview

Mermaid allows you to create diagrams and visualizations using text and code, similar to Markdown. In Wiki4AI documents, wrap your Mermaid code in ` ```mermaid ` code blocks:

```markdown
```mermaid
flowchart TD
    A --> B
```
```

## Supported Diagram Types

### 1. Flowchart (flowchart)

Create flowcharts and process diagrams with nodes and connections.

```mermaid
flowchart TD
    Start([Start]) --> Check{Condition?}
    Check -->|Yes| Action1[Do Something]
    Check -->|No| Action2[Do Other Thing]
    Action1 --> End([End])
    Action2 --> End
```

**Key syntax:**
- `TD` = top-down, `LR` = left-right, `BT` = bottom-top, `RL` = right-left
- `-->` for arrows, `-.->` for dashed, ooo----> for dotted
- Node shapes: `(round)`, `[rect]`, `({diamond})`, `([parallelogram])`, `((circle))`

### 2. Sequence Diagram (sequenceDiagram)

Show interactions between participants over time.

```mermaid
sequenceDiagram
    participant User
    participant API
    participant DB
    User->>API: Send Request
    API->>DB: Query Data
    DB-->>API: Return Results
    API-->>User: Response
```

**Key syntax:**
- `participant` or `actor` to define participants
- `->>` for solid arrows, `-->>` for dashed (responses)
- `Note right of X:` for annotations

### 3. Class Diagram (classDiagram)

Model class relationships and structures.

```mermaid
classDiagram
    class Animal {
        +String name
        +int age
        +makeSound() void
    }
    class Dog {
        +String breed
        +fetch() void
    }
    Animal <|-- Dog
    Dog --> Toy: playsWith
```

**Key syntax:**
- `<|--` inheritance, `*--` composition, `o--` aggregation, `-->` association
- `+` public, `-` private, `#` protected, `~` package-private
- `{abstract}` for abstract classes/methods

### 4. Gantt Chart (gantt)

Visualize project timelines and schedules.

```mermaid
gantt
    title Project Timeline
    dateFormat  YYYY-MM-DD
    section Planning
    Requirements      :a1, 2026-01-01, 14d
    Design            :after a1, 10d
    section Development
    Sprint 1          :a2, after a1, 14d
    Sprint 2          :after a2, 14d
```

**Key syntax:**
- `section` to group tasks
- `:name, start, duration` or `:after dependency, duration`
- Use `crit` for critical path items

### 5. State Diagram (stateDiagram-v2)

Model state transitions in systems.

```mermaid
stateDiagram-v2
    [*] --> Idle
    Idle --> Active: Start
    Active --> Paused: Pause
    Paused --> Active: Resume
    Active --> [*]: Stop
```

**Key syntax:**
- `[*]` represents the initial and final states
- `State1 --> State2: Event/Label` for transitions
- Use `note right of State:` for annotations

## Tips for AI Agents

1. **Use simple node IDs without diacritics or special characters.** Prefer `StartNode` over `ŠtartovýUzel`.
2. **Test your syntax** before embedding in documents. Mermaid is strict about formatting.
3. **Keep diagrams focused** — one diagram per concept, avoid overcrowding.
4. **Use consistent styling** — pick a direction (TD/LR) and stick with it within one diagram.
5. **Always wrap in ` ```mermaid ` blocks** — without the language tag, Mermaid won't render.
6. **Avoid circular references** in flowcharts unless intentional (use different arrow styles to distinguish).

## Common Errors and Solutions

| Error | Cause | Fix |
|-------|-------|-----|
| Diagram doesn't render | Missing ` ```mermaid ` wrapper | Add the language tag before your code block |
| Invalid character in ID | Diacritics or spaces in node names | Use alphanumeric IDs: `Node1` not `Môj Uzel` |
| Circular dependency error | Flowchart has a cycle without proper syntax | Break cycles with intermediate nodes or use subgraphs |
| Missing closing bracket | Unclosed node definition `(Round Node` | Ensure all brackets are paired: `(Round Node)` |
| Syntax error on arrow | Using `->` instead of `-->` | Mermaid requires double arrows: `A --> B` |

## Quick Reference

- **Flowchart:** `flowchart TD`, nodes with `-->` connections
- **Sequence:** `sequenceDiagram`, participants with `->>` messages
- **Class:** `classDiagram`, classes with `<|--` inheritance
- **Gantt:** `gantt`, sections with dated tasks
- **State:** `stateDiagram-v2`, states with `-->` transitions

For more examples and advanced features, visit: https://mermaid.js.org/
"""


# ─── PlantUML Guide Tool (WIKI4AI-63) ────────────────────────────────────────

def get_plantuml_guide() -> str:
    """Get a complete PlantUML diagram guide for AI agents.

    Returns a comprehensive guide covering PlantUML syntax with working examples
    for the UML diagram types that Mermaid does not cover (use case, component,
    deployment) plus class and sequence diagrams. In Wiki4AI documents, wrap the
    PlantUML code in ` ```plantuml ` code blocks — the WebUI renders it as SVG
    via the self-hosted kroki service (nginx proxies /plantuml/ to the kroki
    container).

    Returns:
        Complete PlantUML guide as a markdown-formatted string.
    """
    return """# PlantUML Diagram Guide for AI Agents

## Overview

PlantUML lets you write UML and other diagrams in plain text. In Wiki4AI documents, wrap your PlantUML code in ` ```plantuml ` code blocks:

```markdown
```plantuml
@startuml
Bob -> Alice : Do you see me?
@enduml
```
```

**Rendering:** the WebUI sends the block to the self-hosted **kroki** service
(`GET /plantuml/svg/<deflate+base64url>`) and renders the returned SVG. If kroki
is unavailable, the raw source is shown as a fallback — the document never breaks.

PlantUML complements Mermaid: use ` ```mermaid ` for flowcharts/gantt/state/sequence
and ` ```plantuml ` when you need **use case, component, deployment or object**
diagrams (or richer UML class diagrams). Every diagram MUST start with `@startuml`
and end with `@enduml`.

## Supported Diagram Types

### 1. Class Diagram

Model classes, interfaces and their relationships.

```plantuml
@startuml
class Payment {
    +string id
    +float amount
    +charge() void
}
interface Payable {
    <<interface>>
    +getAmount() float
}
class Order {
    +string id
    +items: List<Item>
    +total() float
}
Payable <|.. Payment
Order "1" --> "*" Payment : has
@enduml
```

**Key syntax:**
- `+` public, `-` private, `#` protected, `~` package-private
- `<<interface>>`, `<<abstract>>`, `<<enum>>` stereotypes
- `<|..` implements, `<|--` inheritance, `*--` composition, `o--` aggregation, `-->` association
- Multiplicity in quotes: `Order "1" --> "*" Payment : has`

### 2. Sequence Diagram

Show interactions between participants over time.

```plantuml
@startuml
actor User
participant "API Gateway" as API
participant OrderService as OS
database DB

User -> API: POST /orders
API -> OS: createOrder()
OS -> DB: INSERT order
DB --> OS: ok
OS --> API: 201 Created
API --> User: order id
@enduml
```

**Key syntax:**
- `actor`, `participant X as Alias`, `database X`, `queue X`, `storage X`
- `->` solid arrow, `-->` dashed (return), `->>` async
- `alt/else/end`, `loop X times`, `opt`, `group` for fragments
- `note over A, B: text` for annotations

### 3. Use Case Diagram

Model system functionality and actors — NOT available in Mermaid.

```plantuml
@startuml
left to right direction
actor Customer
actor Admin

rectangle "Online Shop" {
    usecase "Browse catalog" as UC1
    usecase "Place order" as UC2
    usecase "Pay" as UC3
    usecase "Manage inventory" as UC4
}

Customer --> UC1
Customer --> UC2
UC2 ..> UC3 : <<include>>
Admin --> UC4
@enduml
```

**Key syntax:**
- `actor Name`, `usecase "Name" as Alias` (one word — `use case` with a space is NOT valid)
- `rectangle "System name" { ... }` groups use cases
- `actor --> usecase` association, `usecase ..> other : <<include>>` / `<<extend>>`
- `left to right direction` flips the layout

### 4. Component Diagram

Show software components and their dependencies — NOT available in Mermaid.

```plantuml
@startuml
package "Frontend" {
    [WebUI] as FE
}
package "Backend" {
    [API Gateway] as GW
    [Order Service] as OS
    [Payment Service] as PS
}
database "PostgreSQL" as DB

FE --> GW : HTTPS / REST
GW --> OS
GW --> PS
OS --> DB
PS ..> "External PSP" : <<async>>
@enduml
```

**Key syntax:**
- `[Component Name]` square brackets, `package "Name" { ... }` grouping
- `database "Name"`, `cloud "Name"`, `node "Name"` for other shapes
- `-->` dependency/solid arrow, `..>` dashed dependency

### 5. Deployment Diagram

Show runtime infrastructure (nodes, artifacts) — NOT available in Mermaid.

```plantuml
@startuml
node "Load Balancer" as LB {
}
node "App Server 1" as S1 {
    artifact "app.jar" as A1
    database "local cache" as C1
}
node "App Server 2" as S2 {
    artifact "app.jar" as A2
}
database "PostgreSQL (primary)" as PG

LB --> S1
LB --> S2
S1 --> PG
S2 --> PG
@enduml
```

**Key syntax:**
- `node "Name" { ... }` hosts, `artifact "name.jar"` deployables inside nodes
- `cloud`, `frame`, `storage` for other infrastructure shapes
- Arrows show communication paths between nodes/artifacts

## Tips for AI Agents

- ALWAYS wrap diagrams in `@startuml` / `@enduml` — missing tags are the #1 rendering error.
- Use simple ASCII identifiers (no diacritics/spaces) or quote names: `participant "API Gateway" as API`.
- Keep diagrams focused: one diagram per concern; split large class diagrams with `package`.
- For labels with special characters, use quotes: `A --> B : "label: with colon"`.
- If a block renders as raw text in the WebUI, kroki is likely down — check the hint shown under the error box.

## Common Errors and Solutions

| Symptom | Cause | Fix |
|---------|-------|-----|
| Diagram doesn't render (raw text shown) | Missing `@startuml`/`@enduml` or kroki offline | Add the tags; if kroki is down, the raw source + hint is shown as fallback |
| "Syntax Error" from kroki | Invalid PlantUML syntax (unclosed quotes/brackets, bad arrow) | Validate against plantuml.com; check quoted labels and paired brackets |
| Use case diagram fails to render | `use case` written with a space | The declaration keyword is one word: `usecase "Name" as Alias` |
| Wrong diagram type assumed | First line after `@startuml` doesn't declare the type | Start with an explicit declaration (`class`, `actor`, `node`, ...) or a typed keyword |
| UML relation not drawn | Mermaid-style arrows used in PlantUML | Use PlantUML arrows: `<|--`, `*--`, `o--`, `-->`, `..>` (not `<|--`-only mermaid subset) |

## Quick Reference

- **Class:** `class X { +field }`, `A <|-- B` inheritance, `A *-- B` composition
- **Sequence:** `A -> B: msg`, `B --> A: reply`, `alt/else/end` fragments
- **Use case:** `actor A`, `usecase "X" as UC1`, `A --> UC1`, `UC1 ..> UC2 : <<include>>`
- **Component:** `[Comp]`, `package "P" { }`, `C1 --> C2`
- **Deployment:** `node "N" { artifact "a.jar" }`, `N1 --> N2`

For more examples and advanced features, visit: https://plantuml.com/
"""


# ─── Import Tools ─────────────────────────────────────────────────────────────

def import_document(project_slug: str, title: str, content: str) -> dict:
    """Import a document from raw markdown content string directly into a project.

    Use this when you have complete markdown content in memory and want to create a document
    without going through file upload. Both title AND content are required (unlike create_document).

    Difference from create_document(): import_document requires content parameter, while
    create_document allows content to be optional (creates empty document if omitted).

    Args:
        project_slug: The URL-friendly slug of the target project (required). Get from list_projects().
        title: The title of the document to create (required). A unique slug is auto-generated.
        content: The raw markdown content for the document (REQUIRED). Supports Mermaid diagrams in ` ```mermaid ` blocks.

    Returns:
        Created DocumentDTO with id, title, slug, projectId, createdAt, updatedAt.

    Example:
        import_document("my-project", "API Guide", "# API Reference\n\n## Endpoints\n...")
    """
    body = {"title": title, "content": content}
    return _api_request("POST", f"/v1/projects/{project_slug}/documents", body)


def move_document(project_slug: str, doc_slug: str, target_project_slug: str) -> dict:
    """Move a document from one project to another. Document is REMOVED from source project.

    The document is removed from the source project and added to the target project.
    Wiki links within the source project are preserved where possible (links pointing TO this doc remain).

    Args:
        project_slug: The URL-friendly slug of the SOURCE project (required). Get from list_projects().
        doc_slug: The URL-friendly slug of the document to move (required). Get from list_documents().
        target_project_slug: The URL-friendly slug of the TARGET project (required). Must already exist.

    Returns:
        Updated DocumentDTO in the new project with id, title, slug, projectId (changed), createdAt, updatedAt.

    Example:
        move_document("draft-project", "my-doc", "published-project")  # Moves doc between projects
    """
    body = {"targetProjectSlug": target_project_slug}
    return _api_request("POST", f"/v1/projects/{project_slug}/documents/{doc_slug}/move", body)


def copy_document(project_slug: str, doc_slug: str, target_project_slug: Optional[str] = None) -> dict:
    """Copy a document to another project (or within the same project). Original is preserved.

    Creates a duplicate of the specified document. The original document remains unchanged.
    If target_project_slug is not provided, copies within the same source project (creates a sibling).

    Args:
        project_slug: The URL-friendly slug of the SOURCE project (required). Get from list_projects().
        doc_slug: The URL-friendly slug of the document to copy (required). Get from list_documents().
        target_project_slug: The URL-friendly slug of the TARGET project (optional).
            If not provided, copies within the same source project.

    Returns:
        New DocumentDTO with a new id and slug, title, projectId, createdAt, updatedAt.

    Example:
        # Copy to different project
        copy_document("source-project", "template-doc", "target-project")
        # Duplicate within same project
        copy_document("my-project", "chapter-1")  # Creates 'chapter-1-copy' or similar
    """
    body = {}
    if target_project_slug is not None:
        body["targetProjectSlug"] = target_project_slug
    return _api_request("POST", f"/v1/projects/{project_slug}/documents/{doc_slug}/copy", body)


# ─── Vault Crypto Helpers ─────────────────────────────────────────────────────
# Vault entries are end-to-end encrypted: username/password/notes are encrypted
# HERE, in this MCP server process, with a key derived from the user's master
# password + a salt synced from the backend. The backend only ever stores and
# returns opaque ciphertext for those three fields - it never sees plaintext.
# title/url/groupPath are NOT encrypted (plain columns), so they can be listed
# and searched without the master password.
#
# The crypto here must byte-for-byte match the webui's implementation
# (frontend/src/services/encryptionService.ts + cryptoApi.ts) so that entries
# created via MCP decrypt correctly in the browser, and vice versa:
#   1. The raw master password is first hashed with SHA-256 (hex-encoded) -
#      this hash, not the raw password, is what's sent to the backend for
#      verify/set AND what's fed into PBKDF2 below.
#   2. PBKDF2-HMAC-SHA256, 100_000 iterations, 32-byte key, salted with the
#      Base64 salt from GET /vault/master-password/salt.
#   3. AES-256-GCM, 12-byte random IV. The backend persists a single IV per
#      entry shared across all three fields, so all three must be encrypted
#      with the same IV.
#   4. Each field's plaintext is JSON-encoded before encryption (so a bare
#      string becomes `"like this"`) and JSON-decoded after decryption -
#      matching JSON.stringify()/JSON.parse() on the frontend.

import json as _vault_json
import secrets as _vault_secrets

try:
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
except ImportError:
    AESGCM = None

_VAULT_PBKDF2_ITERATIONS = 100_000
_VAULT_KEY_LENGTH = 32  # AES-256
_VAULT_IV_LENGTH = 12  # AES-GCM standard nonce size


def _vault_require_crypto():
    if AESGCM is None:
        raise MCPToolError(
            "The 'cryptography' package is required for vault operations. "
            "Install it with: pip install cryptography"
        )


def _vault_password_hash(master_password: str) -> str:
    """Reproduce the frontend's SHA-256(hex) preprocessing of the raw master password."""
    import hashlib
    return hashlib.sha256(master_password.encode("utf-8")).hexdigest()


def _vault_derive_key(master_password_hash: str, salt_b64: str) -> bytes:
    """Derive the AES-256 vault key via PBKDF2-HMAC-SHA256 (100k iterations)."""
    import hashlib
    salt = base64.b64decode(salt_b64)
    return hashlib.pbkdf2_hmac(
        "sha256", master_password_hash.encode("utf-8"), salt, _VAULT_PBKDF2_ITERATIONS, dklen=_VAULT_KEY_LENGTH
    )


def _vault_get_key(master_password: str) -> bytes:
    """Verify the master password against the backend and derive the vault encryption key.

    Raises MCPToolError with a clear, actionable message if the vault has not
    been set up yet or the master password is incorrect.
    """
    if not master_password:
        raise MCPToolError("master_password is required for this operation")

    password_hash = _vault_password_hash(master_password)

    try:
        _api_request("POST", "/v1/vault/master-password/verify", {"masterPasswordHash": password_hash})
    except MCPToolError as e:
        if e.status_code == 401:
            # Two very different failures both surface as HTTP 401 here:
            #   1. Spring Security rejects the request before it reaches the controller
            #      because of no/invalid/expired JWT - e.g. {"message": "Authentication
            #      required"} (missing token) or {"error": "Invalid or expired JWT token"}
            #      (malformed/expired token). Both have a JSON body.
            #   2. VaultController.verifyMasterPassword() itself returns a *bodyless* 401
            #      when the password hash doesn't match - the only 401 this endpoint ever
            #      emits with an empty body.
            # Collapsing both into "Incorrect vault master password" is misleading when
            # the real problem is that this MCP session isn't authenticated at all - so
            # treat "got a JSON body" as "auth-layer rejection", not "wrong password".
            if e.details:
                raise MCPToolError(
                    "Not authenticated with the Wiki4AI backend (missing, invalid or "
                    "expired JWT token for this MCP session) - this is a login/session "
                    "problem, not necessarily a wrong master password. "
                    f"Backend said: {e.details}"
                )
            raise MCPToolError("Incorrect vault master password")
        raise

    try:
        salt_response = _api_request("GET", "/v1/vault/master-password/salt")
    except MCPToolError as e:
        if e.status_code == 404:
            raise MCPToolError(
                "Vault master password verified, but no encryption salt is stored for this "
                "user (pre-dates salt syncing). Set up the vault again via the webui "
                "(Vault page) to fix this."
            )
        raise

    return _vault_derive_key(password_hash, salt_response["salt"])


def _vault_encrypt_field(value: str, key: bytes, iv: bytes) -> dict:
    """Encrypt one vault field value into the wire format the backend expects: {ciphertext, iv} (Base64)."""
    _vault_require_crypto()
    plaintext = _vault_json.dumps(value).encode("utf-8")
    ciphertext = AESGCM(key).encrypt(iv, plaintext, None)
    return {
        "ciphertext": base64.b64encode(ciphertext).decode("ascii"),
        "iv": base64.b64encode(iv).decode("ascii"),
    }


def _vault_decrypt_field(field: Optional[dict], key: bytes) -> Optional[str]:
    """Decrypt one vault field. Returns None if the field is absent or fails to decrypt."""
    if not field or not field.get("ciphertext") or not field.get("iv"):
        return None
    _vault_require_crypto()
    try:
        ciphertext = base64.b64decode(field["ciphertext"])
        iv = base64.b64decode(field["iv"])
        plaintext = AESGCM(key).decrypt(iv, ciphertext, None)
        return _vault_json.loads(plaintext.decode("utf-8"))
    except Exception:
        return None


def _vault_entry_metadata(entry: dict) -> dict:
    """Metadata-only view of a vault entry (no decryption - these fields are plaintext on the backend)."""
    return {
        "id": entry.get("id"),
        "title": entry.get("title"),
        "url": entry.get("url"),
        "groupPath": entry.get("groupPath"),
        "createdAt": entry.get("createdAt"),
        "updatedAt": entry.get("updatedAt"),
    }


def _vault_decrypt_entry(entry: dict, key: bytes) -> dict:
    """Full decrypted view of a vault entry, including plaintext username/password/notes."""
    return {
        "id": entry.get("id"),
        "title": entry.get("title"),
        "url": entry.get("url"),
        "groupPath": entry.get("groupPath"),
        "username": _vault_decrypt_field(entry.get("usernameEncrypted"), key),
        "password": _vault_decrypt_field(entry.get("passwordEncrypted"), key),
        "notes": _vault_decrypt_field(entry.get("notesEncrypted"), key),
        "createdAt": entry.get("createdAt"),
        "updatedAt": entry.get("updatedAt"),
    }


# ─── Vault Tools ──────────────────────────────────────────────────────────────

def vault_status() -> dict:
    """Check whether the current user has a vault master password set up.

    Call this before any other vault_* tool that takes a master_password, so you
    can give the user a clear message instead of a confusing error. MCP tools
    intentionally do NOT create the initial master password - choosing and
    confirming it is a one-time, high-stakes action best done in the webui
    (Vault page -> Set up Vault), which has proper confirm-password UX.

    Returns:
        Dict with "hasMasterPasswordSet" (bool).
    """
    is_set = _api_request("GET", "/v1/vault/master-password/status")
    return {"hasMasterPasswordSet": bool(is_set)}


def vault_list_entries() -> list[dict]:
    """List all vault entries (metadata only: title, url, group, timestamps).

    Does NOT require the master password and does NOT return usernames/passwords -
    those are end-to-end encrypted and only decrypted by vault_get_entry(). Use
    this to browse/organize entries before fetching a specific one's credentials.

    Returns:
        List of dicts with id, title, url, groupPath, createdAt, updatedAt.

    Example:
        entries = vault_list_entries()
        # Use entries[0]['id'] with vault_get_entry() to read its credentials
    """
    entries = _api_request("GET", "/v1/vault/entries")
    return [_vault_entry_metadata(e) for e in entries]


def vault_search_entries(query: str, group_path: Optional[str] = None) -> list[dict]:
    """Search vault entries by title/URL (metadata only, no master password needed).

    Args:
        query: Search text matched against title and URL (case-insensitive, required).
        group_path: Optional group_path prefix filter (e.g., '/Work').

    Returns:
        List of matching entries (metadata only - see vault_list_entries).

    Example:
        vault_search_entries("github")
    """
    from urllib.parse import quote_plus as _quote_plus
    params = f"?q={_quote_plus(query)}"
    if group_path:
        params += f"&groupPath={_quote_plus(group_path)}"
    entries = _api_request("GET", f"/v1/vault/search{params}")
    return [_vault_entry_metadata(e) for e in entries]


def vault_get_entry(entry_id: int, master_password: str) -> dict:
    """Get a single vault entry WITH its decrypted username/password/notes.

    Requires the vault master password (same one used to unlock the webui vault)
    to derive the decryption key. Decryption happens locally in this MCP server
    process - the backend never sees the plaintext.

    Args:
        entry_id: Numeric ID of the entry (from vault_list_entries/vault_search_entries).
        master_password: The vault master password.

    Returns:
        Dict with id, title, url, groupPath, username, password, notes (plaintext), createdAt, updatedAt.

    Example:
        vault_get_entry(42, "my-master-password")
    """
    key = _vault_get_key(master_password)
    entry = _api_request("GET", f"/v1/vault/entries/{entry_id}")
    return _vault_decrypt_entry(entry, key)


def vault_create_entry(
    title: str,
    password: str,
    master_password: str,
    username: Optional[str] = None,
    notes: Optional[str] = None,
    url: Optional[str] = None,
    group_path: Optional[str] = None,
) -> dict:
    """Add a new password entry to the vault, encrypted client-side (here) before sending.

    The resulting entry is immediately visible and decryptable in the webui
    vault with the same master password - MCP and webui share one vault.

    Args:
        title: Entry title (e.g., "GitHub"), required. Stored in plaintext.
        password: The password to store, required. Encrypted before sending.
        master_password: The vault master password used to derive the encryption key.
        username: Optional username/email for this entry. Encrypted.
        notes: Optional free-text notes. Encrypted.
        url: Optional URL. Stored in PLAINTEXT (used for search/display, not encrypted).
        group_path: Optional folder path (e.g., "/Work"). Stored in PLAINTEXT.

    Returns:
        Dict with id, title, url, groupPath, createdAt, updatedAt (no plaintext credentials echoed back).

    Example:
        vault_create_entry("GitHub", "s3cr3t!", "my-master-password", username="me@example.com")
    """
    key = _vault_get_key(master_password)
    # One IV shared across all fields of this entry - the backend schema only
    # persists a single IV per entry (see VaultEntry.iv), not one per field.
    iv = _vault_secrets.token_bytes(_VAULT_IV_LENGTH)

    body = {
        "title": title,
        "url": url,
        "groupPath": group_path,
        "passwordEncrypted": _vault_encrypt_field(password, key, iv),
    }
    if username:
        body["usernameEncrypted"] = _vault_encrypt_field(username, key, iv)
    if notes:
        body["notesEncrypted"] = _vault_encrypt_field(notes, key, iv)

    created = _api_request("POST", "/v1/vault/entries", body)
    return _vault_entry_metadata(created)


def vault_update_entry(
    entry_id: int,
    master_password: str,
    title: Optional[str] = None,
    username: Optional[str] = None,
    password: Optional[str] = None,
    notes: Optional[str] = None,
    url: Optional[str] = None,
    group_path: Optional[str] = None,
) -> dict:
    """Edit an existing vault entry. Only pass the fields you want to change.

    The backend stores a full snapshot per entry (not a diff), so this tool
    first fetches and decrypts the current entry, merges in your changes, and
    re-submits the whole thing - any field left as None keeps its current value.

    Args:
        entry_id: Numeric ID of the entry to edit.
        master_password: The vault master password.
        title, username, password, notes, url, group_path: New values (optional).
            Leave as None to keep the entry's current value for that field.

    Returns:
        Dict with id, title, url, groupPath, createdAt, updatedAt.

    Example:
        vault_update_entry(42, "my-master-password", password="new-s3cr3t!")
    """
    key = _vault_get_key(master_password)
    current = _api_request("GET", f"/v1/vault/entries/{entry_id}")
    decrypted = _vault_decrypt_entry(current, key)

    new_title = title if title is not None else decrypted["title"]
    new_username = username if username is not None else decrypted["username"]
    new_password = password if password is not None else decrypted["password"]
    new_notes = notes if notes is not None else decrypted["notes"]
    new_url = url if url is not None else decrypted["url"]
    new_group_path = group_path if group_path is not None else decrypted["groupPath"]

    if not new_password:
        raise MCPToolError("This entry has no password to preserve - a password value is required")

    iv = _vault_secrets.token_bytes(_VAULT_IV_LENGTH)
    body = {
        "title": new_title,
        "url": new_url,
        "groupPath": new_group_path,
        "passwordEncrypted": _vault_encrypt_field(new_password, key, iv),
    }
    if new_username:
        body["usernameEncrypted"] = _vault_encrypt_field(new_username, key, iv)
    if new_notes:
        body["notesEncrypted"] = _vault_encrypt_field(new_notes, key, iv)

    updated = _api_request("PUT", f"/v1/vault/entries/{entry_id}", body)
    return _vault_entry_metadata(updated)


def vault_delete_entry(entry_id: int) -> dict:
    """Permanently delete a vault entry. This action is irreversible.

    Does not require the master password (deletion doesn't need decryption).

    Args:
        entry_id: Numeric ID of the entry to delete.

    Returns:
        Confirmation message on success.
    """
    _api_request("DELETE", f"/v1/vault/entries/{entry_id}")
    return {"message": f"Vault entry {entry_id} deleted successfully"}


# ─── MCP Resources (for agents that support resource reading) ────────────────

@mcp.resource("wiki://{project_slug}/{doc_slug}")
async def get_document_markdown_resource(project_slug: str, doc_slug: str) -> str:
    """Read the raw markdown content of a wiki document.

    This is an MCP Resource that agents can read directly without calling a tool.
    Use this for fast access to document markdown content.

    WORKFLOW — Reading all documents in a project via Resources:
        1. list_documents("servers") → get list of slugs from the tools API
        2. Read wiki://servers/overview → returns raw markdown content directly
    
    Args:
        project_slug: URL-friendly slug of the project (e.g., 'servers', 'overview')
        doc_slug: URL-friendly slug of the document (from list_documents())

    Returns:
        Raw markdown text content of the document

    Example — Agent reads a resource directly:
        # Instead of calling get_document() tool, agent can read:
        wiki://servers/overview  → "# Overview\\n\\nThis is the server documentation..."
        wiki://servers/api-reference  → "## API Endpoints\\n\\nGET /api/v1/..."

    Example — Read all docs in project via resources:
        list_documents("servers")  # → [{"slug": "overview", ...}, {"slug": "api-ref", ...}]
        # Then read each resource: wiki://servers/{slug}
    """
    result = _api_request("GET", f"/v1/projects/{project_slug}/documents/{doc_slug}")
    return result.get("content", "")


@mcp.resource("wiki://{project_slug}/{doc_slug}/html")
async def get_document_html_resource(project_slug: str, doc_slug: str) -> str:
    """Read rendered HTML content of a wiki document.

    This is an MCP Resource that agents can read directly without calling a tool.
    Use this when you need formatted/structured output or extracted links.

    WORKFLOW — Reading documents via HTML Resources:
        1. list_documents("servers") → get list of slugs from the tools API
        2. Read wiki://servers/overview/html → returns rendered HTML + link metadata
    
    Args:
        project_slug: URL-friendly slug of the project (e.g., 'servers', 'overview')
        doc_slug: URL-friendly slug of the document (from list_documents())

    Returns:
        Rendered HTML content string with wiki links resolved as <a> tags

    Example — Agent reads an HTML resource directly:
        # Instead of calling get_document_content() tool, agent can read:
        wiki://servers/overview/html  → "<h1>Overview</h1><p>This is the server documentation...</p>"

    Note: For extracted wiki links and linked document details, use the 
          get_document_content() tool instead.
    """
    result = _api_request("GET", f"/v1/projects/{project_slug}/documents/{doc_slug}/content")
    return result.get("htmlContent", "")


# ─── MCP Server Setup ──────────────────────────────────────────────────────

def create_mcp_server() -> FastMCP:
    """Create and configure the Wiki4AI MCP server with all tools.

    Returns the module-level mcp instance that already has resources registered
    via @mcp.resource decorators. Tools are added here for explicit registration.
    """
    # Use the existing module-level mcp instance (resources auto-registered)
    global mcp

    # Register tools using add_tool() for clean tool names (e.g., list_projects instead of tool_list_projects_post)
    mcp.add_tool(health_check)
    mcp.add_tool(list_projects)
    mcp.add_tool(get_project)
    mcp.add_tool(create_project)
    mcp.add_tool(update_project)
    mcp.add_tool(delete_project)
    mcp.add_tool(list_documents)
    mcp.add_tool(create_document)
    mcp.add_tool(batch_create_documents)
    mcp.add_tool(get_document)
    mcp.add_tool(update_document)
    mcp.add_tool(delete_document)
    mcp.add_tool(get_document_content)
    mcp.add_tool(add_link)
    mcp.add_tool(remove_link)
    mcp.add_tool(get_links)
    mcp.add_tool(get_backlinks)
    mcp.add_tool(search_documents)
    mcp.add_tool(search_documents_global)
    mcp.add_tool(upload_image)
    mcp.add_tool(import_document)
    mcp.add_tool(move_document)
    mcp.add_tool(copy_document)
    mcp.add_tool(get_mermaid_guide)
    mcp.add_tool(get_plantuml_guide)
    mcp.add_tool(vault_status)
    mcp.add_tool(vault_list_entries)
    mcp.add_tool(vault_search_entries)
    mcp.add_tool(vault_get_entry)
    mcp.add_tool(vault_create_entry)
    mcp.add_tool(vault_update_entry)
    mcp.add_tool(vault_delete_entry)

    # MCP Resources are auto-registered by FastMCP via @mcp.resource decorators above.
    # No need for explicit add_resource() calls — the decorated functions are already registered.
    return mcp


# ─── Main Entry Point ────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(description="Wiki4AI MCP Server")
    parser.add_argument(
        "--base-url",
        default=DEFAULT_BASE_URL,
        help=f"Base URL of the Wiki4AI backend API (default: {DEFAULT_BASE_URL})",
    )
    parser.add_argument(
        "--token",
        default=None,
        help="JWT Bearer token used as backend identity for Wiki4AI API calls (in SSE mode a client-provided Authorization header takes priority).",
    )
    parser.add_argument(
        "--transport",
        choices=["stdio", "sse"],
        default="stdio",
        help="Transport mode: stdio (default) or sse for HTTP server",
    )
    parser.add_argument(
        "--port",
        type=int,
        default=8090,
        help="Port for SSE transport (default: 8090)",
    )

    args = parser.parse_args()
    set_base_url(args.base_url)

    # Set backend identity JWT token from the CLI argument only.
    # NOTE: MCP_JWT_TOKEN is deliberately NOT used here — it is the access
    # credential for the SSE endpoint (validated by BearerAuthMiddleware),
    # not a backend identity token. Forwarding it to the Wiki4AI backend
    # would break every API call: the backend rejects any Bearer value that
    # is not a valid app-signed JWT with HTTP 401, even on public endpoints.
    if args.token:
        set_jwt_token(args.token)

    mcp = create_mcp_server()

    if args.transport == "sse":
        print(f"Starting Wiki4AI MCP server on port {args.port} (SSE mode)...")
        
        # Use SSE transport with JWT token extraction from request headers
        try:
            import asyncio
            
            # Create the MCP server instance
            mcp_server = create_mcp_server()
            
            # Import Starlette middleware for JWT extraction
            from starlette.middleware import Middleware
            
            # Define our JWT token extraction middleware as a Starlette middleware class
            class JwtTokenMiddleware:
                """Starlette middleware that extracts the backend identity JWT from SSE requests.

                 Priority order (highest to lowest), see select_jwt_token():
                   1. X-Wiki4AI-JWT header — dedicated identity channel for gated
                      instances where MCP_JWT_TOKEN is set: the Authorization header must
                      carry the shared access credential to pass BearerAuthMiddleware, so
                      the client's identity JWT travels in this header instead (WIKI4AI-61)
                   2. Authorization header (Bearer <token>) — used when it carries a real
                      identity JWT (open instances without an access credential)
                   3. Query parameter (?token=<token>) — last-resort fallback

                 The shared access credential is never forwarded as an identity. This allows
                 clients to dynamically provide their own JWT tokens without requiring
                 server-side configuration.
                 """
                
                def __init__(self, app):
                    self.app = app
                
                async def __call__(self, scope, receive, send):
                    if scope["type"] == "http":
                        # Extract JWT token from Authorization header (highest priority)
                        auth_header = ""
                        for key, value in scope.get("headers", []):
                            if key == b"authorization":
                                auth_header = value.decode("utf-8")
                                break
                        
                        # Read the dedicated identity header (X-Wiki4AI-JWT) for gated
                        # instances where Authorization carries the access credential.
                        identity_header = None
                        for key, value in scope.get("headers", []):
                            if key == b"x-wiki4ai-jwt":
                                identity_header = value.decode("utf-8")
                                break

                        # Select the backend identity JWT (identity header > auth header
                        # > query; access-credential guard — see select_jwt_token).
                        query_string = scope.get("query_string", b"").decode("utf-8")
                        token_to_use = select_jwt_token(auth_header, query_string, identity_header)
                        
                        token_var = None  # Initialize for cleanup
                        if token_to_use:
                            token_var = jwt_token_context.set(token_to_use)
                        
                        try:
                            await self.app(scope, receive, send)
                        finally:
                            if token_var is not None:
                                jwt_token_context.reset(token_var)
                    else:
                        await self.app(scope, receive, send)
            
            # Create the SSE app with auth + JWT middleware
            # RequestContextMiddleware is added by FastMCP internally, we add ours on top
            sse_app = mcp_server.http_app(
                transport="sse",
                middleware=[
                    Middleware(BearerAuthMiddleware),  # endpoint access control runs first (outermost)
                    Middleware(JwtTokenMiddleware),    # JWT extraction for backend API calls
                ],
            )
            
            # Run using uvicorn
            import uvicorn
            
            config = uvicorn.Config(
                sse_app,
                host="0.0.0.0",
                port=args.port,
                log_level="info"
            )
            server = uvicorn.Server(config=config)
            asyncio.run(server.serve())
            
        except ImportError:
            print("Warning: Starlette/uvicorn not available. Using default SSE transport.")
            mcp.run(transport="sse", host="0.0.0.0", port=args.port)
        except Exception as e:
            print(f"Warning: SSE transport with JWT failed ({e}). Using default SSE transport.")
            mcp.run(transport="sse", host="0.0.0.0", port=args.port)
    else:
        print("Starting Wiki4AI MCP server (stdio mode)...")
        mcp.run()


if __name__ == "__main__":
    main()
