"""
Wiki4AI MCP Server
==================
MCP (Model Context Protocol) server that exposes Wiki4AI REST API as tools.

Usage:
  python mcp_server.py --base-url http://localhost:8080/api --token eyJhbGci...

Connect your AI agent to this server via stdio or SSE transport.

Authentication:
  - Via CLI argument: --token <jwt_token>
  - Via environment variable: MCP_JWT_TOKEN=<jwt_token>
  - Via client request header (SSE mode): Authorization: Bearer <token>
  
Priority order for JWT token:
  1. Client-provided token (via SSE request headers) — highest priority
  2. CLI argument --token
  3. Environment variable MCP_JWT_TOKEN
  4. No authentication (unauthenticated access)

This allows clients to dynamically provide their own JWT tokens without
requiring server-side configuration.
"""

import argparse
import contextvars
import os
import sys
from typing import Optional

try:
    from fastmcp import FastMCP
except ImportError:
    print("ERROR: fastmcp is required. Install with: pip install fastmcp")
    sys.exit(1)

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
            token_var.reset()


# ─── Health Tools ─────────────────────────────────────────────────────────────

def health_check() -> dict:
    """Check if the Wiki4AI backend is healthy and running."""
    return _api_request("GET", "/health")


# ─── Project Tools ────────────────────────────────────────────────────────────

def list_projects() -> list[dict]:
    """List all wiki projects. Returns a list of project objects with id, name, slug, description, documentCount, createdAt, updatedAt."""
    return _api_request("GET", "/v1/projects")


def get_project(slug: str) -> dict:
    """Get a specific project by its URL-friendly slug.

    Args:
        slug: The URL-friendly slug of the project (e.g., 'python', 'machine-learning')
    """
    return _api_request("GET", f"/v1/projects/{slug}")


def create_project(name: str, description: Optional[str] = None) -> dict:
    """Create a new wiki project.

    Args:
        name: The name of the project (required, max 255 chars)
        description: An optional description of the project (max 1000 chars)
    """
    body = {"name": name}
    if description:
        body["description"] = description
    return _api_request("POST", "/v1/projects", body)


def update_project(slug: str, name: Optional[str] = None, description: Optional[str] = None) -> dict:
    """Update an existing project by its slug.

    Args:
        slug: The URL-friendly slug of the project to update (required)
        name: New name for the project (optional, max 255 chars)
        description: New description for the project (optional, max 1000 chars)
    """
    body = {}
    if name is not None:
        body["name"] = name
    if description is not None:
        body["description"] = description
    return _api_request("PUT", f"/v1/projects/{slug}", body)


def delete_project(slug: str) -> dict:
    """Delete a project by its slug.

    Args:
        slug: The URL-friendly slug of the project to delete (required)

    Returns:
        Confirmation message on success
    """
    _api_request("DELETE", f"/v1/projects/{slug}")
    return {"message": f"Project '{slug}' deleted successfully"}


# ─── Document Tools ───────────────────────────────────────────────────────────

def list_documents(project_slug: str, page: int = 0, size: int = 50) -> list[dict]:
    """List documents in a project (paginated).

    The backend returns a paginated response with DocumentSummaryDTO objects
    that exclude the 'content' field to keep responses lightweight.
    This function extracts the document list from the pagination wrapper
    and strips any 'content' field defensively.

    Args:
        project_slug: The URL-friendly slug of the project (required)
        page: Page number, 0-indexed (default: 0)
        size: Number of documents per page, max 100 (default: 50)

    Returns:
        List of document summaries (id, title, slug, projectId, linkedDocuments, createdAt, updatedAt).
        The 'content' field is explicitly excluded to keep responses small.
    """
    response = _api_request("GET", f"/v1/projects/{project_slug}/documents?page={page}&size={size}")
    # Backend returns a Spring Data Page object: {"content": [...], "totalElements": N, ...}
    documents = response.get("content", [])
    # Strip 'content' field from each document defensively (backend already excludes it)
    return [{k: v for k, v in doc.items() if k != "content"} for doc in documents]


def create_document(project_slug: str, title: str, content: Optional[str] = None) -> dict:
    """Create a new wiki document within a project.

    Args:
        project_slug: The URL-friendly slug of the project (required)
        title: The title of the document (required)
        content: Markdown content for the document (optional)
    """
    body = {"title": title}
    if content is not None:
        body["content"] = content
    return _api_request("POST", f"/v1/projects/{project_slug}/documents", body)


def batch_create_documents(project_slug: str, documents: list[dict]) -> list[dict]:
    """Create multiple wiki documents within a project in a single call.

    Each document dict should have:
        - title (str, required): The title of the document
        - content (str, optional): Markdown content for the document

    Args:
        project_slug: The URL-friendly slug of the project (required)
        documents: List of document dicts with 'title' and optional 'content' keys

    Returns:
        List of created DocumentDTO objects, one per input document.
        Each contains id, title, slug, projectId, createdAt, updatedAt.
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
    """Get a specific document by its slug within a project.

    Args:
        project_slug: The URL-friendly slug of the project (required)
        doc_slug: The URL-friendly slug of the document (required)
    """
    return _api_request("GET", f"/v1/projects/{project_slug}/documents/{doc_slug}")


def update_document(project_slug: str, doc_slug: str, title: Optional[str] = None, content: Optional[str] = None) -> dict:
    """Update an existing document by its slug within a project.

    Args:
        project_slug: The URL-friendly slug of the project (required)
        doc_slug: The URL-friendly slug of the document to update (required)
        title: New title for the document (optional)
        content: New markdown content for the document (optional)
    """
    body = {}
    if title is not None:
        body["title"] = title
    if content is not None:
        body["content"] = content
    return _api_request("PUT", f"/v1/projects/{project_slug}/documents/{doc_slug}", body)


def delete_document(project_slug: str, doc_slug: str) -> dict:
    """Delete a document by its slug within a project.

    Args:
        project_slug: The URL-friendly slug of the project (required)
        doc_slug: The URL-friendly slug of the document to delete (required)

    Returns:
        Confirmation message on success
    """
    _api_request("DELETE", f"/v1/projects/{project_slug}/documents/{doc_slug}")
    return {"message": f"Document '{doc_slug}' deleted successfully"}


def get_document_content(project_slug: str, doc_slug: str) -> dict:
    """Get a document's content with rendered HTML and extracted wiki links.

    Args:
        project_slug: The URL-friendly slug of the project (required)
        doc_slug: The URL-friendly slug of the document (required)

    Returns:
        Document content including id, title, htmlContent (rendered markdown),
        wikiLinks (extracted [[WikiLink]] titles), and linkedDocuments.
    """
    return _api_request("GET", f"/v1/projects/{project_slug}/documents/{doc_slug}/content")


# ─── Link Management Tools ────────────────────────────────────────────────────

def add_link(project_slug: str, doc_slug: str, target_document_id: int) -> dict:
    """Add a wiki link from one document to another within the same project.

    Args:
        project_slug: The URL-friendly slug of the project (required)
        doc_slug: The source document's slug (required)
        target_document_id: The ID of the target document to link to (required)

    Returns:
        Updated source document with the new link included
    """
    body = {"targetDocumentId": target_document_id}
    return _api_request("POST", f"/v1/projects/{project_slug}/documents/{doc_slug}/links", body)


def remove_link(project_slug: str, doc_slug: str, target_document_id: int) -> dict:
    """Remove a wiki link between two documents within the same project.

    Args:
        project_slug: The URL-friendly slug of the project (required)
        doc_slug: The source document's slug (required)
        target_document_id: The ID of the target document to unlink from (required)

    Returns:
        Confirmation message on success
    """
    _api_request("DELETE", f"/v1/projects/{project_slug}/documents/{doc_slug}/links/{target_document_id}")
    return {"message": f"Link removed: '{doc_slug}' → document {target_document_id}"}


def get_links(project_slug: str, doc_slug: str) -> list[dict]:
    """Get all documents that a specific document links to.

    Args:
        project_slug: The URL-friendly slug of the project (required)
        doc_slug: The source document's slug (required)

    Returns:
        List of linked document objects
    """
    return _api_request("GET", f"/v1/projects/{project_slug}/documents/{doc_slug}/links")


def get_backlinks(project_slug: str, doc_slug: str) -> list[dict]:
    """Get all documents that link to a specific document (backlinks).

    Args:
        project_slug: The URL-friendly slug of the project (required)
        doc_slug: The target document's slug (required)

    Returns:
        List of document objects that reference the given document via wiki links
    """
    return _api_request("GET", f"/v1/projects/{project_slug}/documents/{doc_slug}/backlinks")


# ─── Search Tools ─────────────────────────────────────────────────────────────

def search_documents(project_slug: str, keyword: str) -> list[dict]:
    """Search for documents within a project by keyword.

    Searches document titles and content for the given keyword and returns
    matching documents. The keyword must be at least 2 characters long.

    Args:
        project_slug: The URL-friendly slug of the project (required)
        keyword: The search keyword to find in document titles and content (min 2 chars)

    Returns:
        List of matching document objects with id, title, slug, excerpt, createdAt, updatedAt
    """
    from urllib.parse import quote_plus as _quote_plus
    encoded_keyword = _quote_plus(keyword.strip())
    return _api_request("GET", f"/v1/projects/{project_slug}/documents/search?keyword={encoded_keyword}")


# ─── Import Tools ─────────────────────────────────────────────────────────────

def import_document(project_slug: str, title: str, content: str) -> dict:
    """Import a document from raw markdown content string into a project.

    Useful when an AI agent has markdown content in memory and wants to
    import it directly into the wiki without going through file upload.

    Args:
        project_slug: The URL-friendly slug of the target project (required)
        title: The title of the document to create (required)
        content: The raw markdown content for the document (required)

    Returns:
        Created DocumentDTO with id, title, slug, projectId, createdAt, updatedAt
    """
    body = {"title": title, "content": content}
    return _api_request("POST", f"/v1/projects/{project_slug}/documents", body)


def move_document(project_slug: str, doc_slug: str, target_project_slug: str) -> dict:
    """Move a document from one project to another.

    The document is removed from the source project and added to the target
    project. All links within the source project are preserved where possible.

    Args:
        project_slug: The URL-friendly slug of the source project (required)
        doc_slug: The URL-friendly slug of the document to move (required)
        target_project_slug: The URL-friendly slug of the target project (required)

    Returns:
        Updated DocumentDTO in the new project with id, title, slug, projectId, createdAt, updatedAt
    """
    body = {"targetProjectSlug": target_project_slug}
    return _api_request("POST", f"/v1/projects/{project_slug}/documents/{doc_slug}/move", body)


def copy_document(project_slug: str, doc_slug: str, target_project_slug: Optional[str] = None) -> dict:
    """Copy a document to another project (or within the same project).

    Creates a duplicate of the specified document in the target project.
    If target_project_slug is not provided, the document is copied within
    the same source project.

    Args:
        project_slug: The URL-friendly slug of the source project (required)
        doc_slug: The URL-friendly slug of the document to copy (required)
        target_project_slug: The URL-friendly slug of the target project (optional).
            If not provided, copies within the same project.

    Returns:
        New DocumentDTO with id, title, slug, projectId, createdAt, updatedAt
    """
    body = {}
    if target_project_slug is not None:
        body["targetProjectSlug"] = target_project_slug
    return _api_request("POST", f"/v1/projects/{project_slug}/documents/{doc_slug}/copy", body)


# ─── MCP Server Setup ────────────────────────────────────────────────────────

def create_mcp_server() -> FastMCP:
    """Create and configure the Wiki4AI MCP server with all tools."""

    mcp = FastMCP("wiki4ai")

    # Register tools - fastmcp >= 2.12.0 uses @mcp.tool decorator pattern
    # For pre-defined functions, use the tool() method as a decorator wrapper
    for func in [
        health_check,
        list_projects, get_project, create_project, update_project, delete_project,
        list_documents, create_document, batch_create_documents, get_document, update_document,
        delete_document, get_document_content,
        add_link, remove_link, get_links, get_backlinks,
        search_documents,
        import_document,
        move_document,
        copy_document,
    ]:
        mcp.tool()(func)

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
        help="JWT Bearer token for authentication. Overrides MCP_JWT_TOKEN env var.",
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

    # Set JWT token: CLI arg > env var > None (unauthenticated)
    token_from_cli = args.token
    token_from_env = os.environ.get("MCP_JWT_TOKEN") or None
    if token_from_cli:
        set_jwt_token(token_from_cli)
    elif token_from_env:
        set_jwt_token(token_from_env)

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
                """Starlette middleware that extracts JWT token from SSE request headers or query params.
                
                Priority order (highest to lowest):
                  1. Authorization header (Bearer <token>) — highest priority
                  2. Query parameter (?token=<token>) — fallback for clients that can't send headers
                
                This allows clients to dynamically provide their own JWT tokens without
                requiring server-side configuration.
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
                        
                        # Also extract from query parameter as fallback
                        query_string = scope.get("query_string", b"").decode("utf-8")
                        token_from_query = None
                        if "token=" in query_string:
                            for param in query_string.split("&"):
                                if param.startswith("token="):
                                    token_from_query = param[6:]  # Remove "token=" prefix
                        
                        # Determine which token to use (header > query)
                        token_to_use = None
                        if auth_header.startswith("Bearer "):
                            token_to_use = auth_header[7:].strip()
                        elif token_from_query:
                            token_to_use = token_from_query
                        
                        token_var = None  # Initialize for cleanup
                        if token_to_use:
                            token_var = jwt_token_context.set(token_to_use)
                        
                        try:
                            await self.app(scope, receive, send)
                        finally:
                            if token_var is not None:
                                token_var.reset()
                    else:
                        await self.app(scope, receive, send)
            
            # Create the SSE app with JWT middleware
            # RequestContextMiddleware is added by FastMCP internally, we add ours on top
            sse_app = mcp_server.http_app(
                transport="sse",
                middleware=[
                    Middleware(JwtTokenMiddleware),  # JWT extraction runs first (outermost)
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
