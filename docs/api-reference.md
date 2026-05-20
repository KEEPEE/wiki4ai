# Wiki4AI API Reference

Kompletná referencia REST API s príkladmi curl volaní a odpoveďami.

**Base URL:** `http://localhost:8080/api/v1`  
**Swagger UI:** http://localhost:8080/swagger-ui.html

---

## Health Check

### GET /api/health

Skontroluje stav aplikácie.

```bash
curl -X GET http://localhost:8080/api/health
```

**Odpoveď (200 OK):**
```json
{
  "status": "UP",
  "application": "wiki4ai-backend",
  "version": "0.0.1-SNAPSHOT"
}
```

---

## Projects

### GET /api/v1/projects

Zoznam všetkých projektov.

```bash
curl -X GET http://localhost:8080/api/v1/projects
```

**Odpoveď (200 OK):**
```json
[
  {
    "id": 1,
    "name": "Moja Wiki",
    "slug": "moja-wiki",
    "description": "Prvý wiki projekt",
    "createdAt": "2026-05-18T10:00:00",
    "updatedAt": "2026-05-18T10:00:00"
  }
]
```

---

### GET /api/v1/projects/{slug}

Detail projektu podľa slugu.

```bash
curl -X GET http://localhost:8080/api/v1/projects/moja-wiki
```

**Odpoveď (200 OK):**
```json
{
  "id": 1,
  "name": "Moja Wiki",
  "slug": "moja-wiki",
  "description": "Prvý wiki projekt",
  "createdAt": "2026-05-18T10:00:00",
  "updatedAt": "2026-05-18T10:00:00"
}
```

**Odpoveď (404 Not Found):**
```json
{
  "timestamp": "2026-05-18T10:00:00",
  "status": 404,
  "error": "Not Found",
  "message": "Project with slug 'neexistujuci' not found"
}
```

---

### POST /api/v1/projects

Vytvorenie nového projektu.

```bash
curl -X POST http://localhost:8080/api/v1/projects \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Nový Projekt",
    "description": "Popis môjho projektu"
  }'
```

**Odpoveď (201 Created):**
```json
{
  "id": 2,
  "name": "Nový Projekt",
  "slug": "novy-projekt",
  "description": "Popis môjho projektu",
  "createdAt": "2026-05-18T10:05:00",
  "updatedAt": "2026-05-18T10:05:00"
}
```

**Validačné chyby (400 Bad Request):**
```json
{
  "timestamp": "2026-05-18T10:05:00",
  "status": 400,
  "error": "Bad Request",
  "message": "Validation failed"
}
```

---

### PUT /api/v1/projects/{slug}

Aktualizácia projektu.

```bash
curl -X PUT http://localhost:8080/api/v1/projects/moja-wiki \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Moja Wiki (aktualizovaná)",
    "description": "Aktualizovaný popis projektu"
  }'
```

**Odpoveď (200 OK):**
```json
{
  "id": 1,
  "name": "Moja Wiki (aktualizovaná)",
  "slug": "moja-wiki",
  "description": "Aktualizovaný popis projektu",
  "createdAt": "2026-05-18T10:00:00",
  "updatedAt": "2026-05-18T10:10:00"
}
```

---

### DELETE /api/v1/projects/{slug}

Vymazanie projektu.

```bash
curl -X DELETE http://localhost:8080/api/v1/projects/moja-wiki
```

**Odpoveď (204 No Content):** *(žiadne telo odpovede)*

---

## Documents

### POST /api/v1/projects/{projectSlug}/documents

Vytvorenie nového dokumentu v projekte.

```bash
curl -X POST http://localhost:8080/api/v1/projects/moja-wiki/documents \
  -H "Content-Type: application/json" \
  -d '{
    "title": "Úvod",
    "slug": "uvod",
    "content": "# Vitajte v wiki\n\nToto je úvodný dokument.\n\nPozri aj [[Architektúra]]."
  }'
```

**Odpoveď (201 Created):**
```json
{
  "id": 1,
  "title": "Úvod",
  "slug": "uvod",
  "projectId": 1,
  "createdAt": "2026-05-18T10:15:00",
  "updatedAt": "2026-05-18T10:15:00"
}
```

---

### GET /api/v1/projects/{projectSlug}/documents

Zoznam všetkých dokumentov v projekte.

```bash
curl -X GET http://localhost:8080/api/v1/projects/moja-wiki/documents
```

**Odpoveď (200 OK):**
```json
[
  {
    "id": 1,
    "title": "Úvod",
    "slug": "uvod",
    "projectId": 1,
    "createdAt": "2026-05-18T10:15:00",
    "updatedAt": "2026-05-18T10:15:00"
  },
  {
    "id": 2,
    "title": "Architektúra",
    "slug": "architektura",
    "projectId": 1,
    "createdAt": "2026-05-18T10:20:00",
    "updatedAt": "2026-05-18T10:20:00"
  }
]
```

---

### GET /api/v1/projects/{projectSlug}/documents/{docSlug}

Detail dokumentu.

```bash
curl -X GET http://localhost:8080/api/v1/projects/moja-wiki/documents/uvod
```

**Odpoveď (200 OK):**
```json
{
  "id": 1,
  "title": "Úvod",
  "slug": "uvod",
  "projectId": 1,
  "content": "# Vitajte v wiki\n\nToto je úvodný dokument.\n\nPozri aj [[Architektúra]].",
  "createdAt": "2026-05-18T10:15:00",
  "updatedAt": "2026-05-18T10:15:00"
}
```

---

### GET /api/v1/projects/{projectSlug}/documents/{docSlug}/content

Obsah dokumentu s renderovaným HTML a wiki odkazmi.

```bash
curl -X GET http://localhost:8080/api/v1/projects/moja-wiki/documents/uvod/content
```

**Odpoveď (200 OK):**
```json
{
  "id": 1,
  "title": "Úvod",
  "slug": "uvod",
  "htmlContent": "<h1>Vitajte v wiki</h1>\n<p>Toto je úvodný dokument.</p>\n<p>Pozri aj <a href=\"/api/v1/projects/moja-wiki/documents/architektura\">Architektúra</a>.</p>",
  "links": [
    {
      "targetId": 2,
      "targetTitle": "Architektúra",
      "targetSlug": "architektura"
    }
  ]
}
```

---

### PUT /api/v1/projects/{projectSlug}/documents/{docSlug}

Aktualizácia dokumentu.

```bash
curl -X PUT http://localhost:8080/api/v1/projects/moja-wiki/documents/uvod \
  -H "Content-Type: application/json" \
  -d '{
    "title": "Úvod do Wiki4AI",
    "content": "# Vitajte v Wiki4AI\n\nAktualizovaný obsah s novými odkazmi [[Architektúra]] a [[API]]."
  }'
```

**Odpoveď (200 OK):**
```json
{
  "id": 1,
  "title": "Úvod do Wiki4AI",
  "slug": "uvod",
  "projectId": 1,
  "content": "# Vitajte v Wiki4AI\n\nAktualizovaný obsah s novými odkazmi [[Architektúra]] a [[API]].",
  "createdAt": "2026-05-18T10:15:00",
  "updatedAt": "2026-05-18T10:30:00"
}
```

---

### DELETE /api/v1/projects/{projectSlug}/documents/{docSlug}

Vymazanie dokumentu.

```bash
curl -X DELETE http://localhost:8080/api/v1/projects/moja-wiki/documents/uvod
```

**Odpoveď (204 No Content):** *(žiadne telo odpovede)*

---

## Document Links

### POST /api/v1/projects/{projectSlug}/documents/{docSlug}/links

Pridanie prepojenia medzi dokumentmi.

```bash
curl -X POST http://localhost:8080/api/v1/projects/moja-wiki/documents/uvod/links \
  -H "Content-Type: application/json" \
  -d '{
    "targetDocumentId": 2
  }'
```

**Odpoveď (200 OK):** *(vráti aktualizovaný dokument s novým prepojením)*
```json
{
  "id": 1,
  "title": "Úvod",
  "slug": "uvod",
  "projectId": 1,
  "createdAt": "2026-05-18T10:15:00",
  "updatedAt": "2026-05-18T10:35:00"
}
```

---

### GET /api/v1/projects/{projectSlug}/documents/{docSlug}/links

Zoznam prepojení dokumentu.

```bash
curl -X GET http://localhost:8080/api/v1/projects/moja-wiki/documents/uvod/links
```

**Odpoveď (200 OK):**
```json
[
  {
    "id": 2,
    "title": "Architektúra",
    "slug": "architektura",
    "projectId": 1,
    "createdAt": "2026-05-18T10:20:00",
    "updatedAt": "2026-05-18T10:20:00"
  }
]
```

---

### DELETE /api/v1/projects/{projectSlug}/documents/{docSlug}/links/{targetDocId}

Odstránenie prepojenia medzi dokumentmi.

```bash
curl -X DELETE http://localhost:8080/api/v1/projects/moja-wiki/documents/uvod/links/2
```

**Odpoveď (204 No Content):** *(žiadne telo odpovede)*

---

## Kompletný príklad workflow

Tento príklad ukazuje kompletný postup od vytvorenia projektu po prácu s dokumentmi:

```bash
# 1. Vytvoriť projekt
curl -X POST http://localhost:8080/api/v1/projects \
  -H "Content-Type: application/json" \
  -d '{"name": "Moja Wiki", "description": "Prvý wiki projekt"}'

# 2. Vytvoriť úvodný dokument
curl -X POST http://localhost:8080/api/v1/projects/moja-wiki/documents \
  -H "Content-Type: application/json" \
  -d '{"title": "Úvod", "slug": "uvod", "content": "# Vitajte\nToto je úvod."}'

# 3. Vytvoriť druhý dokument s odkazom na prvý
curl -X POST http://localhost:8080/api/v1/projects/moja-wiki/documents \
  -H "Content-Type: application/json" \
  -d '{"title": "Architektúra", "slug": "architektura", "content": "# Architektúra\nPozri [[Úvod]] pre viac info."}'

# 4. Získať renderovaný obsah s wiki odkazmi
curl http://localhost:8080/api/v1/projects/moja-wiki/documents/architektura/content

# 5. Zobraziť všetky dokumenty v projekte
curl http://localhost:8080/api/v1/projects/moja-wiki/documents

# 6. Aktualizovať dokument
curl -X PUT http://localhost:8080/api/v1/projects/moja-wiki/documents/uvod \
  -H "Content-Type: application/json" \
  -d '{"title": "Úvod do Wiki4AI", "content": "# Vitajte v Wiki4AI\nAktualizovaný obsah."}'

# 7. Vymazať projekt (a všetky jeho dokumenty)
curl -X DELETE http://localhost:8080/api/v1/projects/moja-wiki
```

---

## Error Responses

API vracia štandardné HTTP status kódy a JSON chybové odpovede:

| Status | Význam | Príklad |
|--------|--------|---------|
| 200 OK | Úspešná operácia | GET, PUT |
| 201 Created | Záznam vytvorený | POST |
| 204 No Content | Úspešné vymazanie | DELETE |
| 400 Bad Request | Neplatný vstup / validácia | POST, PUT |
| 404 Not Found | Záznam neexistuje | GET, PUT, DELETE |
| 409 Conflict | Duplicitný záznam | POST (rovnaký slug) |

Príklad chybovej odpovede:
```json
{
  "timestamp": "2026-05-18T10:00:00",
  "status": 404,
  "error": "Not Found",
  "message": "Project with slug 'neexistujuci' not found",
  "path": "/api/v1/projects/neexistujuci"
}
```

---

## Authentication (JWT)

Všetky endpointy okrem `/api/v1/auth/**` vyžadujú platný JWT token v `Authorization` hlavičke.

### Register User

#### POST /api/v1/auth/register

Registrácia nového používateľa.

```bash
curl -X POST http://localhost:8080/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "username": "novypouzivatel",
    "email": "user@example.com",
    "password": "SecurePass123!"
  }'
```

**Odpoveď (201 Created):**
```json
{
  "id": 1,
  "username": "novypouzivatel",
  "email": "user@example.com",
  "createdAt": "2026-05-19T10:00:00"
}
```

**Odpoveď (409 Conflict):** *(duplicitné meno alebo email)*
```json
{
  "error": "Username is already taken"
}
```

---

### Login

#### POST /api/v1/auth/login

Prihlásenie a získanie JWT tokenov.

```bash
curl -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "username": "novypouzivatel",
    "password": "SecurePass123!"
  }'
```

**Odpoveď (200 OK):**
```json
{
  "accessToken": "eyJhbGciOi...plny.jwt.token...",
  "refreshToken": "eyJhbGciOi...refresh.token...",
  "user": {
    "id": 1,
    "username": "novypouzivatel",
    "email": "user@example.com",
    "createdAt": "2026-05-19T10:00:00"
  }
}
```

**Odpoveď (401 Unauthorized):** *(nesprávne meno alebo heslo)*
```json
{
  "error": "Invalid username or password"
}
```

---

### Auth Health Check

#### GET /api/v1/auth/health

Kontrola stavu autentikačného servisu. Verejný endpoint (bez autentizácie).

```bash
curl http://localhost:8080/api/v1/auth/health
```

**Odpoveď (200 OK):**
```json
{
  "status": "UP",
  "service": "auth"
}
```

---

## MCP Client JWT Authentication

MCP (Model Context Protocol) klienti sa autentizujú pomocou rovnakého JWT tokenu ako REST API.

### Ako MCP klient získava token

1. **Registrácia** (voliteľné, ak účet už existuje):
   ```bash
   curl -X POST http://server:8080/api/v1/auth/register \
     -H "Content-Type: application/json" \
     -d '{"username": "mcp-agent", "email": "agent@wiki4ai.local", "password": "secure-password"}'
   ```

2. **Login** (získanie tokenu):
   ```bash
   curl -X POST http://server:8080/api/v1/auth/login \
     -H "Content-Type: application/json" \
     -d '{"username": "mcp-agent", "password": "secure-password"}'
   ```

3. **Použitie tokenu** vo všetkých ďalších požiadavkách:
   ```bash
   curl http://server:8080/api/v1/projects \
     -H "Authorization: Bearer eyJhbGciOi..."
   ```

### Token konfigurácia

| Parameter | Výchozí hodnota | Popis |
|-----------|-----------------|-------|
| `jwt.expiration` | 86400000 ms (24h) | Platnosť access tokenu |
| `jwt.refreshExpiration` | 604800000 ms (7 dní) | Platnosť refresh tokenu |
| `jwt.secret` | Konfigurované v prostredí | Tajný kľúč na podpis tokenov |

### Príklad MCP klienta s JWT

```python
import requests

BASE_URL = "http://server:8080/api/v1"

# Step 1: Login and get token
login_response = requests.post(f"{BASE_URL}/auth/login", json={
    "username": "mcp-agent",
    "password": "secure-password"
})
token = login_response.json()["accessToken"]

# Step 2: Use token in all subsequent requests
headers = {"Authorization": f"Bearer {token}"}

# List projects
projects = requests.get(f"{BASE_URL}/projects", headers=headers).json()

# Create a document
requests.post(
    f"{BASE_URL}/projects/my-project/documents",
    headers={**headers, "Content-Type": "application/json"},
    json={"title": "AI Generated Doc", "content": "# Hello from MCP"}
)
```

### Bezpečnostné odporúčania pre MCP klientov

- **Nikdy neukladajte heslá v kóde** - používajte environmentálne premenné alebo secrets manager
- **Token vyprší po 24h** - implementujte automatické obnovenie pomocou refresh tokenu
- **Používajte HTTPS** v produkčnom prostredí pre šifrovanie komunikácie
- **Každý MCP agent by mal mať vlastný účet** s minimálnymi potrebnými oprávneniami

---

## Permissions (RBAC)

Systém používa Role-Based Access Control (RBAC) s týmito úrovňami:

| Oprávnenie | Popis |
|------------|-------|
| `READ` | Čítanie projektov a dokumentov |
| `CREATE` | Vytváranie nových dokumentov |
| `UPDATE` | Aktualizácia existujúcich dokumentov |
| `DELETE` | Mazanie dokumentov |
| `MANAGE` | Všetky oprávnenia + správa iných používateľov |

### Endpoints pre správu oprávnení

#### GET /api/v1/projects/{slug}/permissions

Zoznam všetkých používateľov a ich oprávnení v projekte. Vyžaduje `READ` oprávnenie.

```bash
curl http://localhost:8080/api/v1/projects/moja-wiki/permissions \
  -H "Authorization: Bearer eyJhbGciOi..."
```

**Odpoveď (200 OK):**
```json
[
  {
    "username": "owner",
    "permissions": ["MANAGE"]
  },
  {
    "username": "editor",
    "permissions": ["READ", "CREATE", "UPDATE"]
  }
]
```

---

#### POST /api/v1/projects/{slug}/permissions

Pridanie oprávnení používateľovi. Vyžaduje `MANAGE` oprávnenie.

```bash
curl -X POST http://localhost:8080/api/v1/projects/moja-wiki/permissions \
  -H "Authorization: Bearer eyJhbGciOi..." \
  -H "Content-Type: application/json" \
  -d '{
    "username": "editor",
    "permissions": ["READ", "CREATE"]
  }'
```

**Odpoveď (201 Created):**
```json
{
  "message": "Permissions granted to 'editor'"
}
```

---

#### DELETE /api/v1/projects/{slug}/permissions/{username}

Odstránenie všetkých oprávnení používateľa. Vyžaduje `MANAGE` oprávnenie.

```bash
curl -X DELETE http://localhost:8080/api/v1/projects/moja-wiki/permissions/editor \
  -H "Authorization: Bearer eyJhbGciOi..."
```

**Odpoveď (200 OK):**
```json
{
  "message": "All permissions revoked from 'editor'"
}
```
