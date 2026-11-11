# Wiki4AI API — Pinovanie a manuálne poradie (WIKI4AI-184)

Kontrakt endpointov pre „pin na vrch“ a vlastné poradie projektov aj dokumentov.
Dáta drží Flyway **V26**: pridala stĺpce `pinned BOOLEAN` a `sort_order INTEGER`
na `projects` aj `documents` (obidva `NOT NULL` s defaultom, backfill hustým
0..n-1 podľa dovtedajšieho zobrazenia).

**Base URL:** `http://localhost:8080/api/v1` (dev: `http://192.168.77.219:8086/api/v1`)

---

## Spoločné pravidlá

- **Pinned sú vždy prvé.** Akýkoľvek list (`sort` hocijaký) vráti pripnuté položky
  nad nepripnutými; vnútri oboch skupín rozhoduje zvolený sort.
- **Pin/reorder nie je editácia.** `Project.updatedAt` sa neposúva a
  `Document.version` (optimistické zamykanie) ani `updatedAt` sa **nemení** —
  drag v sidebari nikdy nespôsobí 409 editorovi, ktorý rovnaký dokument práve upravuje.
- **Oprávnenia:** projekty vyžadujú `MANAGE`, dokumenty `UPDATE` v projekte.
  Inak **403**. Cudzí súkromný projekt/dokument je **404** (neodhaľuje existenciu).
- **`sortOrder` je opaque.** V DTO sa vracia pre informáciu, ale klient ho nemá
  písať ani z neho odvodzovať susedov — môže byť záporný a mení sa pri každom
  prečíslení. Jediný spôsob, ako meniť poradie, je reorder endpoint.

## List parametre (projects aj documents)

`GET /api/v1/projects` a `GET /api/v1/projects/{projectSlug}/documents`:

| Parameter | Typ | Default | Význam |
|---|---|---|---|
| `sort` | string | projekty: `created_desc`, dokumenty: `updated_desc` | `manual` \| `name_asc` \| `name_desc` \| `created_asc` \| `created_desc` \| `updated_desc` |
| `q` | string | – | case-insensitive filter (projekty: názov+popis, dokumenty: názov+obsah) |
| `visibility` | string | – | len projekty: `public` \| `private` (inak obe) |

- Neznáma hodnota `sort` alebo `visibility` → **400** so zoznamom povolených hodnôt.
- Bez parametrov sa správajú presne ako pred WIKI4AI-184 (len pinned preskočia na vrch).
- `sort=manual` radí `pinned DESC, sort_order ASC, id ASC`.
- Dokumenty sú stránkované: `page` (0-index), `size` (default 50, **max 100** — clamped).

```bash
curl "http://localhost:8080/api/v1/projects?sort=manual"
curl "http://localhost:8080/api/v1/projects?q=wiki&visibility=private"
curl "http://localhost:8080/api/v1/projects/my-project/documents?sort=name_asc&q=runbook"
```

## Pin / unpin

```bash
POST /api/v1/projects/{slug}/pin
POST /api/v1/projects/{slug}/unpin
POST /api/v1/projects/{projectSlug}/documents/{docSlug}/pin
POST /api/v1/projects/{projectSlug}/documents/{docSlug}/unpin
```

- Telo žiadne, odpoveď **200** s aktualizovaným DTO (`pinned`, `sortOrder`).
- Prvé pripnutie položí položku aj *nad súčasnú prvú pozíciu manuálneho poradia*
  (aby hneď po pripnutí bola viditeľne navrch). Opakované pripnutie je idempotentné
  a pozíciu nemení.
- **Unpin zachová `sortOrder`** — položka sa vrámi presne tam, kde bola pred pripnutím.

## Reorder

```bash
POST /api/v1/projects/{slug}/reorder
POST /api/v1/projects/{projectSlug}/documents/{docSlug}/reorder
```

Telo musí obsahť **práve jeden** zo troch selektorov:

| Kľúč | Význam |
|---|---|
| `beforeSlug` | presun tesne pred položku s týmto slugom (kotva v tej istej skupine) |
| `afterSlug` | presun tesne za položku s týmto slugom |
| `position` | absolútna cieľová pozícia (0-indexovaná; hodnota mimo rozsahu sa zovrie) |

```bash
curl -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"beforeSlug": "delta-notes"}' \
  http://localhost:8080/api/v1/projects/glass-admin/reorder
```

- **Práve jeden selektor.** Nula alebo dva → **400**.
- Kotva (`beforeSlug`/`afterSlug`) sa rieši voči **aktuálnemu** poradiu celého
  zoznamu — nie voči indexu, ktorý mal klient v čase drag startu. Neexistujúca
  kotva → **404**.
- Po presunutí sa **celý zoznam prečísli husto 0..n-1** (permutácia, nikdy nie
  dva rovnaké indexy).
- Manuálne poradie je samo o sebe pinned-first: presun mimo vlastnej skupiny
  (nepripnutý do bloku pripnutých) server akceptuje, ale výsledok je na oko
  to isté poradie. WebUI to rieši tak, že clampuje drop index na hranice skupiny
  (pozri [ui-ordering-pinning.md](ui-ordering-pinning.md)).

## Chybové kódy — zhrnutie

| Kód | Kedy |
|---|---|
| 400 | reorder bez selektora / s viacerými; neznámy `sort` alebo `visibility` |
| 403 | chýba MANAGE (projekty) / UPDATE (dokumenty) |
| 404 | neexistujúci slug, cudzí súkromný projekt, neexistujúca kotva |

## Zdroje v kóde

- `backend/.../controller/ProjectController.java` — `pin/unpin/reorder` (Swagger anotácie sú zdroj pravdy)
- `backend/.../controller/DocumentController.java` — rovnaký kontrakt pre dokumenty
- `backend/.../dto/ReorderRequestDTO.java` — kontrakt tela
- `backend/src/main/resources/db/migration/V26__add_pinning_and_sort_order.sql` — schéma + backfill
