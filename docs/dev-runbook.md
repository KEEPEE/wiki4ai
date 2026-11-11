# Wiki4AI — dev runbook (.219)

Dev inštancia kampaňovej vetvy beží na `192.168.77.219` v dockge stacku
`wiki4ai-campaign` (kontajnery `wiki4ai-campaign-{db,backend,frontend,mcp,embedding,kroki}`,
PG volume `campaign_pgdata`).

- **UI:** http://192.168.77.219:3016/
- **Backend:** http://192.168.77.219:8086 (`/actuator/health`)
- **Prod .4** (192.168.77.4) je samostatná inštancia — pri vývoji sa **nesahá**,
  len čítať (dumpy, `docker ps`).

## Nasadenie dev

Z DSH kontajnera / workspace repa (netreba docker ani java, len tar+ssh+sshpass):

```bash
WIKI4AI_SSH_PW=<heslo hosta> WIKI4AI_SUDO_PW=<heslo> \
  "/root/Wiki4ai /wiki4ai/scripts/dev-deploy-219.sh" all
```

Skript (1) natarí zdroj (backend, frontend, mcp-server, embedding,
docker-compose.yml — bez .git/node_modules/target/dist) do
`~/projects/wiki4ai-campaign/src` na hostovi, (2) spustí host-side
`deploy.sh` (`docker compose build && up -d`), (3) vypíše status kontajnerov.
Subpríkazy: `all | build | up | restart | ps | logs [svc]`.
Heslá ber z vaultu (SSH .219), nikdy ich nepis do skriptov ani dokumentov.

## Prihlásenie

Dev je od 31. 10. 2026 kópiou **produkčných dát** — prihlasuje sa **produkčným
účtom** (vault entry 91, pole username/password). Pôvodný dev bootstrap účet
`admin` (dočasné heslo, už neplatí) zanikol: bootstrap `ADMIN_INITIAL_PASSWORD`
sa pri existujúcich používateľoch nepoužije.

## Obnova prod dát na dev

Postup (použitý pri WIKI4AI-189, overený na 83 projektoch / 773 dokumentoch):

1. **Záloha aktuálneho dev** DB pred obnovou (aby sa dal vrátiť demo stav).
2. **Dump z prod** (len čítacie operácie na .4):
   `docker exec wiki4ai-db pg_dump -U wiki4ai wiki4ai | gzip > wiki4ai-prod-<date>.sql.gz`
3. **Obnova na dev:** `gunzip -c dump.sql.gz | docker exec -i wiki4ai-campaign-db psql -U wiki4ai`
   (dev DB sa pred restore vyčistí / prepíše schéma).
4. **Flyway:** po štarte dev backendu sa schéma sama migruje na najnovšiu
   verziu (prod dump je typicky o 1 migráciu pozadu — napr. V25 → V26
   `add pinning and sort order`). Over v logu backendu a v `information_schema`.
5. **Uploady:** prod bind mount
   `/mnt/ssd240/docker/dockge/stacks/wiki4ai/uploads` → nakopírovať na dev
   `/mnt/data/docker/dockge/stacks/wiki4ai-campaign/uploads` (bind mount
   frontendu, inak obrázky v dokumentoch nie sú).
6. **Overenie:** počty `SELECT COUNT(*)` prod vs dev (projects/documents/users/
   document_links/vault_entries/calendar_events/api_tokens), login prod účtom
   na `:8086/api/v1/auth/login`, `GET /projects` a `?sort=manual`, UI na :3016.

## Zálohy

Na hostovi `.219` v `~/backups/`:

| Súbor | Čo |
|---|---|
| `wiki4ai-prod-<date>.sql.gz` | full dump prod DB (z .4) |
| `wiki4ai-prod-uploads-<date>.tar` | kópia prod uploadov |
| `wiki4ai-dev-before-prod-restore-<date>.sql.gz` | dev stav pred obnovou prod dát |

Pri každej ďalšej obnove prod → dev **najprv zálohuj aktuálny dev** do
`~/backups/` pod vlastným menom s dátumom.

## Rýchle kontroly

```bash
curl -s http://192.168.77.219:8086/actuator/health   # {"status":"UP"}
curl -s -o /dev/null -w '%{http_code}' http://192.168.77.219:3016/   # 200
```

Testy spúšťať z DSH kontajnera: frontend `npm run test:run` (903/903),
backend cez docker na hostovi (1876 testov, 0 failures) — v kontajneri nie je java.
