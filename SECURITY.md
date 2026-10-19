# Security Policy

## Supported versions

| Version | Supported |
|---------|-----------|
| latest `main` | ✅ |
| tagged releases (`vX.Y.Z`) | ✅ |

Wiki4AI is a self-hosted application: you run it, so you control the deployment, the network
boundary and the database. Security fixes land on `main` and ship in the next tag.

## Reporting a vulnerability

Please **do not open a public issue** for anything that exposes data or bypasses authentication.

Report privately by either:

- opening a [GitHub security advisory](https://github.com/KEEPEE/wiki4ai/security/advisories/new), or
- emailing [keepee@gmail.com](mailto:keepee@gmail.com).

Expected response: within 7 days. You will be credited in the release notes unless you ask not to be.

## What this project already does

- **End-to-end encrypted credentials vault** — values are encrypted client-side; the server stores
  ciphertext and never holds the master password.
- **Visibility model** — projects and documents are public/private, enforced server-side on both the
  REST API and the MCP surface; event update/delete is restricted to the owner or ADMIN.
- **Scoped agent access** — MCP tools honour the same permissions as the human account they run as.
- **No telemetry** — nothing leaves your instance.

## Deployment hardening checklist

- Put the app behind TLS (reverse proxy) and keep `docker-compose.yml` secrets out of git.
- Restrict the PostgreSQL port to the app container; do not expose `5432`.
- Set a strong `POSTGRES_PASSWORD`; rotate the vault master password if a device is compromised.
- Back up the database with the scripts in [`scripts/`](scripts) before upgrading.
