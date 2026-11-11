# Changelog

All notable changes to Wiki4AI are documented here. The format follows Keep a Changelog and this project uses Semantic Versioning.

The entries below are derived from the commit history (496 commits since 2026-05-16), grouped by release phase.


## November 2026

Two campaigns landed since October. A hardening sweep closed the security and correctness findings from a full-code review (WIKI4AI-197…202), and a visual pass made the web UI as colourful and alive as its sibling forum4Ai (WIKI4AI-206, 208). Between them: per-user project ordering, and a vault crypto format that can be replaced without touching a single stored password.

### Added

- **ui**: three visual identities on one token layer — `glass-dark` (now the default), `glass-light`, `nexaverse` — selected by `data-theme` on `<html>`, with legacy `dark`/`light` kept as aliases (`WIKI4AI-192`)
- **ui**: an ambient background of three drifting mist gradients, three independently animated orbs, a faint grid and a vignette, all four layers token-driven (`WIKI4AI-193`)
- **ui**: semantic colour triads `--ok` / `--info` / `--warn` / `--error` with `-soft` and `-glow` variants derived by `color-mix` rather than by new hex values, wired into states, badges and toasts (`WIKI4AI-208`)
- **ui**: gradient and glow on the primary action and on the active navigation pill, with dark ink instead of white text (`WIKI4AI-208`, `WIKI4AI-206`)
- **ui**: a quiet zone — `data-mood="quiet"` on document, editor and search routes dims the ambient layer and gives prose and diagrams an opaque panel, so reading stays calm while the dashboards stay loud (`WIKI4AI-208`)
- **ui**: a chart palette `--chart-0..8` per identity, feeding the canvas charts and the share bar (`WIKI4AI-208`)
- **ui**: 39 transitions and 9 animations moved onto motion tokens, so `prefers-reduced-motion` silences motion without touching colour (`WIKI4AI-208`)
- **ui**: shared primitives `.btn`, `.data-table`, `.form-input`, `.state-empty`, `.state-error` and a single badge definition, replacing nine and fourteen duplicated copies (`WIKI4AI-194`)
- **ui**: modals are native `<dialog>` elements; the Vault import and the Markdown toolbar followed the shell (`WIKI4AI-194`)
- **a11y**: a global focus ring through `:where()` after deleting 38 `outline: none` rules, a skip link, `inert` on the off-canvas sidebar, accessible dialog names, and toasts that satisfy WCAG 2.2.1 (`WIKI4AI-195`)
- **ordering**: pin, unpin and reorder projects and documents — endpoints, drag & drop in the UI, a sort menu and server-side filters (`WIKI4AI-184`, `WIKI4AI-186`, `WIKI4AI-187`)
- **ordering**: the project order is now **per viewer** (`user_project_order`, migration V28); arranging your own list needs only READ, while the global order stays an admin endpoint (`WIKI4AI-200`)
- **vault**: crypto v2 — canonical JSON, additional authenticated data, a KEK/DEK split and a per-field IV, with the whole vault re-encrypted at unlock; stored passwords are kept, only the format migrates (`WIKI4AI-197`)
- **api**: `POST /auth/refresh`, the route the frontend had been calling all along (`WIKI4AI-197`)
- **security**: an SVG sanitizer on the diagram `innerHTML` sinks (`WIKI4AI-198`)
- **tests**: `contrastBudget.test.ts` composites the real token values the way a browser would and fails when body text, a primary button or an active pill drops below 4.5:1 (`WIKI4AI-208`); the frontend suite grew from 903 to 1151 tests across the campaign
- **docs**: `docs/design-system.md` is the canonical description of the identities, the token layers, the WCAG floors and the known exceptions — with file names

### Fixed

- **security**: an API token's scopes are enforced and can no longer be outranked by its owner's role; an API token can no longer mint or clone credentials (`WIKI4AI-197`)
- **security**: moving a document requires CREATE on the target project, image uploads require CREATE, and the project member list runs through the visibility gate (`WIKI4AI-197`)
- **security**: the H2 web console is not a deployed endpoint, and a 500 no longer quotes the library that threw (`WIKI4AI-197`)
- **auth**: reserved usernames can be neither written nor authenticated; the JWT layer fails fast when the signing secret is not a secret, parses a token once, and requires an access token to say it is one (`WIKI4AI-197`)
- **auth**: `PUT /api/v1/auth/me` validates its body — `@Valid` was missing (`WIKI4AI-202`)
- **vault**: a v1 update may no longer omit the password, and a master-password reset is refused once v2 owns the vault (`WIKI4AI-197`)
- **ordering**: a reorder runs under a pessimistic lock as a compare-and-set, and `pinned` / `sort_order` stopped being written by entity saves (`WIKI4AI-197`)
- **diagrams**: rendered Mermaid and kroki PlantUML SVG is sanitized before it reaches `innerHTML`, with `securityLevel: strict` kept as a second pass (`WIKI4AI-198`)
- **backend**: the embedding sidecar call leaves the document's transaction, and the dashboard answers one grouped count instead of one query per project (`WIKI4AI-197`)
- **wcag 1.4.3**: the vault unlock button and the active pills measured 2.94:1 to 4.39:1; they now measure 4.64:1 to 7.47:1 in all three identities (`WIKI4AI-206`)
- **a11y**: after Escape, focus returns to the invoker instead of `<body>`; an activated menu item returns focus to its trigger; `<body>` is never treated as an invoker (`WIKI4AI-195`, `WIKI4AI-201`)
- **i18n**: Slovak typo `Káľendar` → `Kalendár` (`WIKI4AI-199`)
- **tests**: the Spring context cache stopped dropping the H2 schema, which had been posing as a flake (`WIKI4AI-197`)

### Changed

- Colours live **only** in `frontend/src/styles/tokens.css`; a guard test caps hard-coded colour values at 98 across 27 files and fails the build when the budget grows undocumented.
- The default theme is `glass-dark`. Saved preferences for `dark` and `light` map onto the new identities instead of breaking.
- Compose publishes only the two ports that need publishing, and `ddl-auto` is no longer overridden with `update` — Flyway owns the schema (`WIKI4AI-197`).

## October 2026

Agent coordination layer — the release that turns Wiki4AI into a shared workspace where agents and humans work side by side (2026-10-25).

### Added

- backend: work items — entity, CRUD and atomic claim with lease TTL, state machine guards and a scheduled reaper (`94cf57f`, `a08e801`)
- backend: structured work item comments with per-kind contracts; done requires a result comment (`3c20b51`, `b97ec4d`)
- backend: append-only work item event audit, timeline API and actor metrics (`dcd51ad`)
- backend: agent actors with project roles, audit attribution and whoami (`7183688`, `b5b817b`)
- backend: hashed w4a_ API tokens with rotation, actor kill switch and per-token rate limits (`ac75744`)
- backend: per-actor inbox with per-stream cursors and unseen-event indexes (`cc0bab9`)
- backend: resources and atomic capacity reservations with TTL release (`f758d89`)
- mcp: work item, inbox and resource tools — 52 tools in total, error codes in tool descriptions (`5fb7520`, `047418a`)
- frontend: coordination board and work item detail with timeline, handoff card and lease countdown (`2745d2c`)
- frontend: agents, resources and metrics pages with token rotation and unseen-events marker (`f09a74d`)
- docs: README rewritten around the coordination layer, 6 new screenshots (`270f34d`)
- docs: seed documentation extended with an agent coordination guide — fresh installs ship it (`0abc738`)

### Fixed

- security: agent token plaintext no longer persisted or rendered in the UI (`16c3ddd`)
- api: unmapped path answers 404 and wrong method 405 instead of 500; append-only repositories refuse bulk mutations (`82097c6`)
- api: readable 403 message, 404 for unknown metrics project, claimToken no longer leaks through GET paths (`8f2f711`)
- frontend: catch-all route renders a real Not Found page, /admin no longer renders blank (`0daebd9`)
- tests: suite no longer depends on class order; timers no longer survive unmount (`0188a3e`, `83e0eb3`)

## September 2026

### Added

- frontend: calendar week view with hourly grid (`452e728`)
- mcp: visibility parameters for projects and documents (`cbaabcc`)
- frontend: visibility toggles + lock badges (`0d8d4f1`)
- backend: public/private visibility for projects and documents (`eb6a531`)
- mcp: calendar tools (`fdea71d`)
- frontend: calendar page with month view + event form (`01b1e65`)
- backend: calendar events + event types REST API (`38c0520`)
- backend: semantic search min cosine similarity threshold (wiki4ai.search.semantic-min-similarity, default 0.35) (`57554ba`)
- frontend: admin users kebab menu + readable option styling (`a030d4f`)
- frontend: replace top-nav auth cluster with single user menu (`0ed950d`)
- compose: complete local stack with MCP server and kroki (`d9a779a`)
- backend: first-run documentation seed — fresh install ships wiki4ai docs project (`ae5a2ac`)
- frontend: i18n with react-i18next — EN default + SK, switchable in profile (`d71e4df`)
- backend: persist user UI language preference (V12 migration, profile + login exposure) (`d9f53b0`)
- …and 16 further features in this phase

### Fixed

- frontend: make compact month pills actually readable on mobile (`45d9709`)
- frontend: calendar mobile header overflow + pill readability polish (`2d3baae`)
- frontend: calendar month pill overflow on long titles (`b522d35`)
- frontend: normalize HH:mm:ss event times for display and form (`825a496`)
- backend: support clearing event time on update (`b672794`)
- backend: restrict event update/delete to owner or ADMIN (`b14cb82`)
- frontend: selects & settings consistency — shared .form-select, danger-zone radius, vault validation states, themed export dropdown (`d2b1683`)
- frontend: portal-render Menu panel to escape table-wrapper overflow clipping (`2b46674`)
- …and 32 further fixes in this phase

## August 2026

### Added

- docs: add find/replace contentEdits partial-update support to document API and MCP (WIKI4AI-24) (`19e3888`)
- docs: make title optional in document update with partial-update semantics (WIKI4AI-23) (`34b21a9`)
- ops: add wiki4ai DB backup/restore scripts for prod->dev migration (`54f2735`)
- vault: sync encryption salt server-side + add MCP tools for vault CRUD (`baca7a9`)
- vault: add real-world KDBX test fixture for import testing (WIKI4AI-17) (`513932c`)
- vault: create DTO classes for vault API (`1279533`)
- vault: implement export entries to CSV/JSON (WIKI4AI-14) (`40e7de4`)
- add Vault navigation item to header with icon (`6f5ee27`)
- vault: add KDBX import endpoint and UI (`f4b60b3`)
- vault: add KeePassJava2 dependency and VaultImportService for KDBX import (`0d417d6`)
- vault: implement master password management for users (`2d8a976`)
- vault: create VaultEntryForm component for create/edit entries (`c4f8f6e`)
- vault: create VaultPage with entries list, grouping, search, and copy-to-clipboard (`dc27e4b`)
- vault: implement useVaultEntries React Query hook (`a1368b3`)
- …and 6 further features in this phase

### Fixed

- vault: replace broken hand-rolled crypto fallback with @noble/hashes+ciphers (`2e48423`)
- mcp: distinguish auth failures from wrong vault master password (`873a1e2`)
- vault: fix invisible edit icon (CSS collision) + add delete entry (`6b7bc15`)
- vault: fix KDBX import 500 and make edit button reliably visible (`6d5ef84`)
- vault: fix broken add-password flow (React error #310 + backend contract mismatch) (`482742a`)
- vault: handle missing salt gracefully for new browser/device access (`0796cac`)
- vault: resolve React error #310 by moving hooks before conditional returns (`0fec876`)
- vault: use Arrays.equals instead of Objects.equals for byte array comparison (`21d8409`)
- …and 7 further fixes in this phase

## July 2026


### Fixed

- mcp: use ContextVar.reset(token) instead of token.reset() (`6cdf0de`)

## June 2026

### Added

- add autosave toggle and configurable interval to DocumentEditor (`abab9aa`)
- frontend: enhance Markdown editor theme and toolbar styling (`ac95fcc`)
- frontend: Add MarkdownToolbar component with 10 formatting buttons (`5302f60`)
- add get_mermaid_guide MCP tool with complete Mermaid diagram guide (`d40c1df`)
- frontend: replace textarea with Monaco Editor in DocumentEditor (`fcb9b60`)
- frontend: add MarkdownPreview component with react-markdown, Mermaid and GFM support (`0cc5220`)
- frontend: add MermaidDiagram component with SVG rendering and error handling (`bdc5521`)
- frontend: add MarkdownEditor component with Monaco Editor wrapper (`bfee830`)
- frontend: install @monaco-editor/react and remark-gfm dependencies (`26b5bc6`)
- exclude content field from list_documents paginated response (`8324d8f`)
- frontend: persist named API tokens in localStorage with copy-anywhere UX (`be16cba`)
- add named API token management with multiple tokens and lifetime support (`d3f2679`)
- add optional token expiration date for API token generation (`df57f36`)

### Fixed

- frontend: Mermaid re-render — stable ID, DOM cleanup, race condition fix (`1f72897`)
- MermaidDiagram: use dark theme with custom variables for readability on glass background (`d5f427f`)
- use ResizeObserver for Monaco Editor height instead of CSS percentage heights (`e58a0e0`)
- Monaco Editor always receives height prop to prevent collapse (`997184d`)
- Monaco editor height collapse in document editor (`1e6a26a`)
- document viewer preview display and editor height (`5892844`)
- DocumentEditor split view panes now fill full height (`ac8abb3`)
- generate infinite-expiry JWT tokens for named API tokens without expiration (`f8b11a3`)
- …and 3 further fixes in this phase

## May 2026

### Added

- replace single toggle with three view mode buttons (Edit/Preview/Split) (`d0f3b06`)
- ux: style Edit/Save tlačidlá — glassmorphism, neon hover a gradient active stav (`944a1f9`)
- frontend: implement document link management (add/remove/view links) (`98a3c7e`)
- frontend: add Breadcrumb component with glassmorphism styling (`8e8e665`)
- graph: dark neon theme with glassmorphism panel for GraphView (`7ef88c9`)
- frontend: style MarkdownViewer with Nexaverse glassmorphism design (`75265aa`)
- frontend: extract Toast component with glassmorphism neon styling and animations (`43b366d`)
- style metrics strip with Nexaverse gradient text design (`8d81e73`)
- frontend: apply glassmorphism + neon accent to modals and form inputs (Nexaverse style) (`2329664`)
- Dashboard: neon search bar s cyan glow focus efektom (`b0aafd6`)
- enhance project cards with glassmorphism and neon glow hover effects (`8ab33c2`)
- frontend: Add BackButton component with glassmorphism neon hover styling (`f15f7bc`)
- frontend: Add Tag component and apply glassmorphism neon badge styling (`3e3133d`)
- frontend: Add hexagon SVG WikiLogo component with neon glow & gradient (`1cd44d5`)
- …and 64 further features in this phase

### Fixed

- wire up JWT middleware for SSE transport with query param fallback (`ee063d5`)
- Use TransactionTemplate for refresh token delete to fix login 500 error (`9dcda22`)
- Add Flyway migration V3 to create refresh_tokens table (`10e0e65`)
- remove unused fireEvent import in AdminUsersPage test (`7a346ac`)
- correct Flyway version to 10.15.2 for modular PostgreSQL support (`ed7c108`)
- add Flyway migrations to fix missing 'role' column in users table (`5586772`)
- allow READ permission for any authenticated user (`f6002e5`)
- correct JWT token extraction (substring 6->7) and add MCP JWT auth documentation (`3263768`)
- …and 38 further fixes in this phase
