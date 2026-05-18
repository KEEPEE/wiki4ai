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
