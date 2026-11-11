# Wiki4AI WebUI — pinovanie, poradie, drag&drop, filtre (WIKI4AI-186/187)

Dashboard projektov aj zoznam dokumentov v projekte zdieľajú rovnaký model:
**pripnuté položky sú vždy navrch**, zvyšok sa radiť dá serverovo a pri
`sort=manual` sa dá poradie meniť myšou.

## Pinovanie

- Tlačidlo `PinButton` na karte projektu (`data-testid="pin-project-<slug>"`),
  na riadku dokumentu (`pin-doc-<slug>`) aj v hlavičke DocumentViewer.
- Prístupnosť: `aria-pressed` + aria-label „Pin/Unpin project|document X to/from the top“.
- **Optimistic update s rollbackom:** klik ihneď prehodí kartu/riadok, pri
  chybe (typicky **403** — cudzí projekt) sa stav vráti späť.
- `pinnedFirst` je **stabilná partition** (`src/utils/pinSort.ts`): UI len
  prepláva pinned skupinu na vrch, vnútorne poradie oboch skupín necháva na
  serveri — žiadne lokálne triedenie, ktoré by sa rozchádzalo s API.
- Unpin položku vráti presne tam, kde bola pred pripnutím (server zachová
  `sortOrder`).

## Zoradenie a filtre

- Menu `data-testid="sort-select"` (Dashboard aj ProjectDetail). Voľba sa
  perzistuje v `localStorage`: `wiki4ai-projects-sort`, `wiki4ai-docs-sort`
  (`src/utils/listSort.ts`). Default je serverový (`created_desc` / `updated_desc`).
- Vyhľadávanie posielá serveru **`?q=`** (debounced), nie lokálny filter —
  radí sa teda nad celým zoznamom, nie nad načítanou stránkou.
- Filter viditeľnosti `data-testid="visibility-filter"` (len projekty).
- Aktívny filter sa zobrazuje ako **chip** + „Clear filters“; zmazať stačí jeden click.

## Drag&drop (HTML5)

- Úchyty: `drag-handle-project-<slug>` (dashboard karta), `drag-handle-doc-<slug>`
  (ProjectDetail riadok). DnD je čisté HTML5 (žiadna knižnica).
- Drop **na** riadok znamená „zaber jeho slot“ — UI pošle reorder s kotvou
  suseda (`beforeSlug`/`afterSlug`), nikdy index. Index by bol okamžite zlý,
  keby medzitým niekto iný poradie zmenil.
- Po úspešnom reorderi sa list **nepreberá zvonku** — optimistic presun +
  server je autorita pri ďalšom načítaní.

### Kedy je DnD zamknuté (a prečo)

`dndEnabled = sort === 'manual' && !filterActive` — pri inom sorte alebo pri
aktívnom filtri (`?q=`, `visibility`) majú všetky úchyty
`data-dnd-enabled=false` a miznú. Dôvod: reorder API prečísla **celé** manuálne
poradie, ale zobrazený je len jeho filtrovaný prierez — drag v filtrovanom
zobrazení by znel „presun za X“, hoci X je v skutočnosti sused len vo výbere.
Jednoznačné je to len pri nezfiltrovanom `manual` liste.

### Pinned a skupiny

- Pinned položky sa presúvajú **len vnútri svojej skupiny**. Drop mimo skupiny
  sa clampne na hranicu skupiny (limit je viditeľný, nie tichý no-op).
- Cross-group drag sa z UI ani neodosiela — keby sme poslali reorder, server
  ho akceptuje a vráti to isté poradie, čo vyzerá ako chyba.

## Známe obmedzenia (akceptované)

1. **Subprojekty nie sú drag source** — úchyt je len na koreňových kartách;
   subprojekty sa zobrazujú vnútri karty otca a ich poradie riadi server.
2. **DnD je vypnuté pri aktívnom filtri** a pri ne-manuálnom sorte (vyššie).
3. **>100 dokumentov:** backend má max `size=100` na stránku, ProjectDetail
   načítava prvých 100. DnD sa vzťahuje len na načítané okno a UI pri tom
   zobrazí hlášku. Presun mimo okno = najprv zmeň sort/filter alebo hľadaj.
4. **`sortOrder` je opaque** (môže byť záporný, mení sa pri každom prečíslení) —
   nikde v UI sa z neho nepodvodzujú susedia; jediná cesta je reorder API.
5. DnD funguje len s prihláseným účtom s `MANAGE`/`UPDATE` oprávnením; bez
   neho je úchyt síce zamknutý pravidlami vyššie, ale pri 403 z API sa
   optimistic stav vráti späť.

## Zdroje v kóde

- `src/utils/pinSort.ts`, `src/utils/listSort.ts`, `src/utils/dragOrder.ts` — čistá logika (+ testy `pinSort.test.ts`, `listSort`/`dragOrder` testy)
- `src/hooks/usePinToggle.ts` — optimistic pin s rollbackom
- `src/pages/Dashboard.tsx`, `src/pages/ProjectDetail.tsx` — toolbar, DnD handler, testidy
- Kontrakt API: [api-ordering-pinning.md](api-ordering-pinning.md)
