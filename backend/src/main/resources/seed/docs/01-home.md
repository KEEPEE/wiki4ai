# Home — wiki4ai Documentation

Welcome to the official documentation of **wiki4ai** — a self-hosted collaborative wiki built for teams *and* AI agents. This project is the complete, in-product reference: how the platform works, its technical internals, and how to operate it.

## What is wiki4ai?

wiki4ai is a self-hosted knowledge base where documents are written in **Markdown**, organized into **projects** (with optional subproject hierarchies), and cross-linked with `[[WikiLink]]` syntax that the platform resolves into navigable links and an interactive graph.

It is designed around two first-class interfaces:

- **A modern web UI** for humans — project dashboard, split-view Markdown editor with live preview and autosave, rendered diagrams, hybrid search, user administration.
- **An MCP (Model Context Protocol) server** exposing the entire platform as **32 tools**, so AI agents can read, write, link, search, and manage the wiki exactly like a human would — through the same backend REST API.

The whole stack runs from a single Docker Compose file: an nginx + React frontend, a Spring Boot REST backend, PostgreSQL 16 with the pgvector extension, an optional local embedding sidecar for semantic search, and an optional kroki service for PlantUML rendering. Optional components are genuinely optional — wiki4ai degrades gracefully when they are absent (see [[Architecture Overview]]).

## Features

| Feature | What it does |
|---|---|
| Projects & subprojects | Knowledge is organized into projects; projects can be nested as subprojects up to **5 levels deep**, with cycle protection. Renaming a project never changes its slug/URL. |
| Markdown editor with autosave | Split-view editor with live preview, debounced autosave (default 2 s, toggleable and configurable per user), unsaved-change detection, and conflict-aware saving. |
| Mermaid diagrams | ` ```mermaid ` code blocks render **client-side** in the browser as SVG — no server component required. |
| PlantUML via kroki | ` ```plantuml ` code blocks render through an optional self-hosted [kroki](https://kroki.io) service (nginx proxies `/plantuml/` to it). When kroki is down, a graceful error state with the raw source is shown instead of a crash. |
| Images & uploads | Documents can embed uploaded images (up to 10 MB); files are stored on a host volume and served through JWT-protected endpoints. |
| Hybrid search | **Global** (cross-project) and **per-project** document search combining literal text matching with semantic vector similarity (pgvector cosine, HNSW index), fused with Reciprocal Rank Fusion. Works cross-lingually; degrades to text-only when the embedding sidecar is unavailable. |
| Accounts & roles | First-run setup flow creates the initial ADMIN account on an empty instance; public registration is closed afterwards by default (opt-in via `auth.registration.open`). Admins manage users and assign **USER** or **ADMIN** roles. Per-project permission grants (READ/CREATE/UPDATE/DELETE/MANAGE) let admins share projects selectively. |
| Optimistic concurrency control | Documents carry a version number; updates can declare an `expectedVersion`. Stale writes fail with **HTTP 409** instead of silently overwriting another writer — enforced for both the web UI and MCP agents. |
| MCP server | Python (FastMCP) server exposing 32 tools over stdio or SSE, including full document CRUD, links, search, image upload, subproject management, and an end-to-end-encrypted secret vault. Optional Bearer-token gating of the SSE endpoint. |
| Encrypted secret vault | Per-user vault entries (passwords, tokens) encrypted client-side with a user-chosen master password; the backend never sees plaintext. Accessible from the web UI and via MCP tools. |
| Diagram linking & graph view | `[[WikiLink]]` references are extracted server-side into explicit link relations, powering backlinks, document link lists, and an interactive force-directed graph of a project's documents. |
| Docker deployment | One compose file for the full stack; images tagged by commit SHA for trivial rollbacks; Flyway-managed database migrations (schema V1–V11). |
| Graceful degradation | The embedding sidecar and kroki are optional: without them, search becomes text-only (with a visible banner) and PlantUML blocks show an error state — everything else keeps working. |

## Documentation map

This project contains the full documentation set. Each document below is linked with wiki-link syntax; links to documents that do not exist yet will resolve automatically as soon as they are created.

| # | Document | What it covers |
|---|---|---|
| 1 | **Home** (this page) | Overview, feature list, and this documentation map. |
| 2 | [[Getting Started]] | Prerequisites, first-run setup flow (first admin account), basic usage walkthrough, and the accounts & roles reference table. |
| 3 | [[Architecture Overview]] | System components with diagrams, request flow, search and save data flows, and key design decisions. |
| 4 | [[Backend API Reference]] | Complete REST API reference: authentication, projects, documents, search, images, vault, admin endpoints — with request/response examples. |
| 5 | [[Data Model]] | Database schema (Flyway migrations V1–V11), entities and relations, the pgvector embedding column, and link storage. |
| 6 | [[Search & Embeddings]] | How hybrid search works in depth: text path, vector path, RRF fusion, the embedding sidecar, backfill, and degradation behavior. |
| 7 | [[WebUI Guide]] | Page-by-page guide to the web interface: dashboard, projects, editor/viewer, graph view, search, admin screens, vault. |
| 8 | [[MCP Server]] | The MCP server for AI agents: transport options, authentication, all 32 tools with usage notes and examples. |
| 9 | [[Deployment & Operations]] | Docker Compose deployment, configuration via environment variables, upgrades and rollbacks (commit-SHA image tags), backups, health checks, and operations runbooks. |

## Where to look next

- New to wiki4ai? Start with [[Getting Started]].
- Integrating an AI agent? Jump to [[MCP Server]].
- Planning a production deployment? Read [[Deployment & Operations]] together with [[Architecture Overview]].
