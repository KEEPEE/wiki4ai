# Wiki4AI — E2E kontrolný list kampane (epik WIKI4AI-182)

Finálny E2E presvit dev inštancie http://192.168.77.219:3016/ po kampaňovej
zmene. Scenáre sú overené v jednotlivých story (komentáre + prílohy v Jire);
manager robí finálne screenshoty sám a dopĺňa ich ako prílohy na WIKI4AI-188.

Stav: ✅ = overené v story a potvrzené managerom, 🔁 = overené unit/integráciami,
screenshot doplní manager.

## 1. Témy (WIKI4AI-185)

| Scenár | Očakávanie | Stav | Dôkaz |
|---|---|---|---|
| Prvé otvorenie (prázdny localStorage) | `data-theme="nexaverse"`, bg `rgb(10,10,18)` | ✅ | WIKI4AI-185 — manager komentár + screenshoty Dashboard v 3 témach |
| Prepínač v sidebari aj na Login/Register | 3 segmenty, prepnutie ihneď prekreslí celú appku | ✅ | 185 prílohy; testid `theme-switch`, `theme-option-<id>`, `auth-theme-switch` |
| Perzistencia po reload | voľba prežíva v `localStorage.wiki4ai-theme` | ✅ | 185 manager komentár |
| FOUC | prvý paint už v uloženej téme (guard v index.html) | ✅ | 185 (IIFE pred paintom) |
| Editor (Monaco) v 3 témach | palety `wiki4ai-{dark,forest,light}`, žiadny červený fallback | ✅ | 185 prílohy (Editor screenshoty) |
| Mermaid + GraphView temou-vedomé | diagramy/graph prekreslia po zmene témy | ✅ | 185 (applyMermaidTheme, buildNodePalette) |
| Auth stránky v light/dark | čitateľný kontrast | ✅ | 185 prílohy (Login) |

## 2. Pinovanie (WIKI4AI-186)

| Scenár | Očakávanie | Stav | Dôkaz |
|---|---|---|---|
| Pin projektu na dashboard | karta okamžite navrch, `data-pinned=true`, vydrží po reload | ✅ | WIKI4AI-186 — manager komentár + prílohy (Ordering navrch) |
| Unpin projektu | karta klesne na pôvodné miesto (sortOrder zachovaný) | ✅ | 186 prílohy |
| Pin/unpin dokumentu v ProjectDetail | riadok navrch s indikáciou; `pin-doc-<slug>` v hlavičke Vieweru | ✅ | 186 prílohy |
| 403 pri cudzom projekte | optimistic rollback, UI sa vráti späť | 🔁 | unit testy `usePinToggle.test.tsx` (7) |
| Prístupnosť | `aria-pressed` + aria-label „Pin/Unpin …“ | ✅ | 186 manager komentár |

## 3. Sort, filtre, DnD (WIKI4AI-187)

| Scenár | Očakávanie | Stav | Dôkaz |
|---|---|---|---|
| Drag projektu myšou | `POST /projects/<slug>/reorder {"beforeSlug":…}` 200, poradie pretrvá po reload | ✅ | WIKI4AI-187 — manager vlastný drag (Glass Admin → nad Delta Notes) + prílohy |
| Drag dokumentu | reorder `{"afterSlug":…}` 200, clampnutý do nepripnutej skupiny (pinned ostáva navrch) | ✅ | 187 manager vlastný drag (Runbook) |
| Cross-group drag | nepripnutý nad pripnutým → **žiadny** reorder request (žiadny tichý no-op) | ✅ | 187 manager komentár |
| `sort=name_asc` | pinned prvý, zvyšok abecedne | ✅ | 187 prílohy |
| `visibility=private` | len súkromné + chip „Visibility: Private“ + Clear filters | ✅ | 187 prílohy |
| DnD zamknuté pri ne-manuálnom sorte / filtri | všetky handly `data-dnd-enabled=false` | ✅ | 187 manager komentár |
| Perzistencia sortu | `wiki4ai-projects-sort` / `wiki4ai-docs-sort` v localStorage | ✅ | 187 + unit testy |
| >100 dokumentov | DnD len pre načítaných 100, UI zobrazí hlášku | 🔁 | unit testy; reálny projekt s >100 doc na dev nemáme (najväčší prod projekt je menší) |

## 4. Backend kontrakt (WIKI4AI-184)

| Scenár | Očakávanie | Stav | Dôkaz |
|---|---|---|---|
| pin/unpin/reorder projekty aj dokumenty | 200 + DTO; version/updatedAt sa nemení | ✅ | 184 (1876 backend testov) + 189 API overenie na prod dátach |
| reorder bez / s viacerými selektormi | 400 | ✅ | 184 testy |
| neznámy `sort`/`visibility` | 400 so zoznamom hodnôt | ✅ | 184 testy |
| `?q=`, `?visibility=`, pinned prvé | 200, správne rady | ✅ | 184 testy + 189 (`?q=byt` 3 zásahy, `?visibility=private` 0) |
| Flyway V26 na reálnych dátach | migrácia V25→V26 prebehne, stĺpce existujú | ✅ | WIKI4AI-189 komentár (log backendu) |

## 5. Infra / prod dáta na dev (WIKI4AI-189)

| Scenár | Očakávanie | Stav | Dôkaz |
|---|---|---|---|
| Počty prod → dev | 83 projektov, 773 dokumentov, 4 users, 395 links, 122 vault entries, 15 events, 2 tokens — zhodné | ✅ | WIKI4AI-189 tabuľka COUNT + manager komentár |
| Login produkčným účtom na dev | 200 + token; dashboard ukazuje skutočné prod projekty | ✅ | 189 prílohy (dashboard s prod dátami) |
| Uploady na dev | 10 súborov v `uploads/byt/`, DocumentViewer vykreslí 6/6 obrázkov | ✅ | 189 manager komentár + prílohy |
| Prod .4 nedotknutá | len čítacie operácie; start-time kontajnerov identické, :3016 200 pred aj po | ✅ | 189 dôkaz v komentári |
| Obrázky priamou cestou `/images/...` | 404 (nginx neproxyje) — **aj na produkcii**, rieši samostatná úloha | ⚠️ open | **WIKI4AI-190** (backlog) |

## 6. Finálna regresia (WIKI4AI-188, 2026-10-31)

| Suite | Výsledok |
|---|---|
| Frontend `npm run test:run` | **903/903 green** (72 súborov), 0 failures |
| Backend `mvn -B test` (docker, main `054c97b`) | **1876 testov, 0 failures, 0 errors** (21 skipped — Testcontainers-dependent), BUILD SUCCESS |
| TypeScript `tsc -b` | čistý (súčasť buildu) |

## Otvorené po kampaňovej zmene

- **WIKI4AI-190** — nginx neposkytuje `/images/<projekt>/<uuid>.jpg` (404 aj na
  prod); v UI netrpá, SPA cesty prepisuje `imageApi.ts`.
- **Nasadenie na produkciu .4** — samostatný krok po E2E, nie súčasť kampane;
  prod zatiaľ beží na starej verzii (pred V26).
- Kozmetika: light téma — sivý header tabuľky (záznam v 185, nebráni Done).
