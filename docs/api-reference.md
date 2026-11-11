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

Zoznam projektov. Od WIKI4AI-184 podporuje filter a poradie.

| Parameter | Typ | Default | Význam |
|---|---|---|---|
| `sort` | string | `created_desc` | `manual` \| `name_asc` \| `name_desc` \| `created_asc` \| `created_desc` \| `updated_desc` |
| `q` | string | – | case-insensitive filter na **názov + popis** projektu |
| `visibility` | string | – | `public` \| `private` (inak obe) |

**Vždy** platí, že pripnuté projekty (`pinned: true`) sú na vrchu a až potom nasleduje
zvolený sort. Bez parametrov je poradie totožné s predchádzajúcou verziou
(`created_at DESC`), len pinned projekty preskočia na vrch. Neznáma hodnota `sort`
alebo `visibility` je 400 a chybová správa vypíše povolené hodnoty.

```bash
curl -X GET http://localhost:8080/api/v1/projects
curl -X GET "http://localhost:8080/api/v1/projects?sort=manual"
curl -X GET "http://localhost:8080/api/v1/projects?sort=name_asc&q=wiki&visibility=public"
```

**Odpoveď (200 OK):**
```json
[
  {
    "id": 1,
    "name": "Moja Wiki",
    "slug": "moja-wiki",
    "description": "Prvý wiki projekt",
    "parentSlug": null,
    "depth": 1,
    "documentCount": 3,
    "ownerId": 1,
    "visibility": "public",
    "pinned": true,
    "sortOrder": -1,
    "createdAt": "2026-05-18T10:00:00",
    "updatedAt": "2026-05-18T10:00:00"
  }
]
```

`pinned` a `sortOrder` sú read-only — menia ich iba endpointy pin/unpin/reorder nižšie.
`sortOrder` sa číta len pri `sort=manual`; nie je to spojitá číselná rada, ale pozícia
v zozname, ktoré reorder prečísli husto 0..n-1. Položka práve vytvorená alebo práve
pripnutá má pozíciu `min - 1` (teda zápornú) — je to zámer, aby bola na vrchu.

---

### POST /api/v1/projects/{slug}/pin

Pripnú projekt na vrch všetkých zoznamov (WIKI4AI-184). Vyžaduje **MANAGE** oprávnenie;
cudzí súkromný projekt vráti 404.

```bash
curl -X POST http://localhost:8080/api/v1/projects/moja-wiki/pin \
  -H "Authorization: Bearer $TOKEN"
```

**Odpoveď (200 OK):** projekt s `pinned: true`.

Pri prvom pripnutí projekt získa aj pozíciu nad súčasnou prvou položkou manuálneho
poradia, takže je na vrchu aj pri `sort=manual`. Opakované pripnutie pozíciu nemení.
`updatedAt` projektu sa **neposúva** — pin nie je editácia, a `updated_desc` je jeden
zo sortov, takže by úprava času ticho prehodila zoznam, ktorý sa práve prehadzujú.

---

### POST /api/v1/projects/{slug}/unpin

Odopnú projekt. `sortOrder` zostáva zachovaný, takže sa projekt vráti presne tam,
kde bol pred pripnutím. Vyžaduje **MANAGE**.

```bash
curl -X POST http://localhost:8080/api/v1/projects/moja-wiki/unpin \
  -H "Authorization: Bearer $TOKEN"
```

---

### POST /api/v1/projects/{slug}/reorder

Presunie projekt v manuálnom poradí (WIKI4AI-184). Vyžaduje **MANAGE**.
Telo musí obsahovať **práve jeden** zo troch selektorov:

| Telo | Význam |
|---|---|
| `{"beforeSlug": "iný-slug"}` | presun tesne pred tento projekt |
| `{"afterSlug": "iný-slug"}` | presun tesne po tomto projekte |
| `{"position": N}` | presun na absolútnu pozíciu (0-indexovanú) |

```bash
curl -X POST http://localhost:8080/api/v1/projects/moja-wiki/reorder \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"beforeSlug": "druhy-projekt"}'
```

**Odpoveď (200 OK):** presunutý projekt s novou hodnotou `sortOrder`.

Po presunutí sa celý zoznam prečísla husto 0..n-1, takže uložený stav je vždy permutácia
bez duplicit. `position` mimo rozsahu sa **zovrie** na hranicu (presun za koniec = drop na
koniec), nie je to chyba. Presun relatívne k vlastnému slugu je no-op. Chyby:
400 (telelo bez selektora alebo s viacerými), 404 (projekt neexistuje alebo kotva
`beforeSlug`/`afterSlug` neexistuje), 403 (chýba MANAGE).

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

Paginovaný zoznam dokumentov v projekte. Od WIKI4AI-184 podporuje filter a poradie.

| Parameter | Typ | Default | Význam |
|---|---|---|---|
| `page` | int | `0` | číslo stránky (0-indexované) |
| `size` | int | `50` | veľkosť stránky (max 100) |
| `sort` | string | `updated_desc` | `manual` \| `name_asc` \| `name_desc` \| `created_asc` \| `created_desc` \| `updated_desc` |
| `q` | string | – | case-insensitive filter na **názov aj obsah** dokumentu |

Pripnuté dokumenty sú vždy na vrchu. Poradie sa vyhodnocuje v SQL, preto je stabilné aj
medzi stránkami — bez toho by `sort=manual` medzi stránkou 1 a 2 prehadzovalo riadky.
Filter `q` zužuje aj `totalElements`, nie len riadky na stránke.

```bash
curl -X GET http://localhost:8080/api/v1/projects/moja-wiki/documents
curl -X GET "http://localhost:8080/api/v1/projects/moja-wiki/documents?sort=manual"
curl -X GET "http://localhost:8080/api/v1/projects/moja-wiki/documents?sort=name_asc&q=routing&page=0&size=20"
```

**Odpoveď (200 OK):**
```json
[
  {
    "id": 1,
    "title": "Úvod",
    "slug": "uvod",
    "projectId": 1,
    "visibility": "public",
    "pinned": true,
    "sortOrder": -1,
    "linkedDocuments": [],
    "createdAt": "2026-05-18T10:15:00",
    "updatedAt": "2026-05-18T10:15:00"
  },
  {
    "id": 2,
    "title": "Architektúra",
    "slug": "architektura",
    "projectId": 1,
    "visibility": "public",
    "pinned": false,
    "sortOrder": 0,
    "linkedDocuments": [],
    "createdAt": "2026-05-18T10:20:00",
    "updatedAt": "2026-05-18T10:20:00"
  }
]
```

---

### POST /api/v1/projects/{projectSlug}/documents/{docSlug}/pin

Pripnú dokument na vrch zoznamu v rámci projektu (WIKI4AI-184). Vyžaduje **UPDATE**
oprávnenie v projekte.

```bash
curl -X POST http://localhost:8080/api/v1/projects/moja-wiki/documents/uvod/pin \
  -H "Authorization: Bearer $TOKEN"
```

**Odpoveď (200 OK):** dokument s `pinned: true`.

Dôležité: `version` (optimistické blokovanie, WIKI4AI-72) sa **nenavyšuje** a `updatedAt`
sa neposúva. Pin ani reorder sa totiž ukladajú priamym UPDATE v repozitári, nie
load-and-save — inak by prehadzovanie dokumentov v bočnom paneli rozbilo práve prebiehajúcu
editáciu obsahu toho istého dokumentu chybovou 409.

---

### POST /api/v1/projects/{projectSlug}/documents/{docSlug}/unpin

Odopnú dokument. `sortOrder` zostáva zachovaný. Vyžaduje **UPDATE**. `version` sa
nenavyšuje.

```bash
curl -X POST http://localhost:8080/api/v1/projects/moja-wiki/documents/uvod/unpin \
  -H "Authorization: Bearer $TOKEN"
```

---

### POST /api/v1/projects/{projectSlug}/documents/{docSlug}/reorder

Presunie dokument v manuálnom poradí **v rámci jeho projektu** (WIKI4AI-184).
Vyžaduje **UPDATE**. Telo má rovnakú semantiku ako reorder projektu — práve jeden zo
selektorov `beforeSlug` / `afterSlug` / `position`:

```bash
curl -X POST http://localhost:8080/api/v1/projects/moja-wiki/documents/uvod/reorder \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"afterSlug": "architektura"}'
```

**Odpoveď (200 OK):** presunutý dokument s novou hodnotou `sortOrder`.

Poradie dokumentov projektu sa prečísla husto 0..n-1. Kotou môže byť iba dokument
z toho istého projektu — cudzí slug je 404. `version` sa ani tu nenavyšuje.

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
