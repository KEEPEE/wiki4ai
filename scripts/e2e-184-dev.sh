#!/usr/bin/env bash
# WIKI4AI-184 — end-to-end check of pin-to-top + custom order on the dev stack.
#
# Run ON the dev host (192.168.77.219). The admin password is read from the stack's
# compose.yaml and never printed. The script only reports projects and documents it
# created itself (tagged with a run id), so pre-existing dev data is untouched.
#
#   ADMIN_PW=... bash e2e-184-dev.sh [base-url]      # default http://localhost:8086
set -uo pipefail

BASE="${1:-http://localhost:8086}"
COMPOSE="${COMPOSE:-/mnt/data/docker/dockge/stacks/wiki4ai-campaign/compose.yaml}"
RUN="w184$$"
FMT=/tmp/w184_fmt_$$.py

cat > "$FMT" <<'PY'
import json, sys
raw = sys.stdin.read()
try:
    d = json.loads(raw)
except Exception:
    print("NON-JSON:", raw[:200]); raise SystemExit(0)
if isinstance(d, dict) and "message" in d:
    print("ERR:", d["message"]); raise SystemExit(0)
rows = d.get("content", d) if isinstance(d, dict) and "totalElements" in d else d
if isinstance(rows, dict):
    rows = [rows]
out = []
for x in rows:
    bits = [str(x.get("slug"))]
    if "pinned" in x:
        bits.append("pinned=%s" % x.get("pinned"))
    if "sortOrder" in x:
        bits.append("order=%s" % x.get("sortOrder"))
    if "version" in x and x.get("version") is not None:
        bits.append("v%s" % x.get("version"))
    out.append("(%s)" % ",".join(bits))
print(" ".join(out) if out else "(empty)")
PY

ADMIN_PW="${ADMIN_PW:-$(sudo -n grep -m1 'ADMIN_INITIAL_PASSWORD' "$COMPOSE" 2>/dev/null | sed 's/.*[:=][[:space:]]*//' | tr -d '"' | tr -d "'")}"
[ -n "$ADMIN_PW" ] || { echo "FATAL: no ADMIN_PW (pass it in the environment)"; rm -f "$FMT"; exit 1; }

TOKEN="$(curl -s -X POST "$BASE/api/v1/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"admin\",\"password\":\"$ADMIN_PW\"}" \
  | python3 -c 'import json,sys; print(json.load(sys.stdin).get("accessToken",""))')"
[ -n "$TOKEN" ] || { echo "FATAL: login failed"; rm -f "$FMT"; exit 1; }
AUTH=(-H "Authorization: Bearer $TOKEN")

post() { curl -s -X POST "$BASE$1" "${AUTH[@]}" -H 'Content-Type: application/json' ${2:+-d "$2"} | python3 "$FMT"; }
get()  { curl -s "$BASE$1" "${AUTH[@]}" | python3 "$FMT"; }
raw()  { curl -s -X POST "$BASE$1" "${AUTH[@]}" -H 'Content-Type: application/json' ${2:+-d "$2"}; }

echo "== WIKI4AI-184 E2E on $BASE (run tag $RUN) =="

echo; echo "-- 1. three projects --"
for n in Alpha Bravo Charlie; do
  curl -s -o /dev/null -X POST "$BASE/api/v1/projects" "${AUTH[@]}" -H 'Content-Type: application/json' \
    -d "{\"name\":\"$RUN $n\",\"description\":\"story 184 $n\"}"
done
echo "created      : $(get "/api/v1/projects?q=$RUN")"

echo; echo "-- 2. pin the oldest (alpha) --"
post "/api/v1/projects/$RUN-alpha/pin"
echo "default      : $(get "/api/v1/projects?q=$RUN")"
echo "created_asc  : $(get "/api/v1/projects?q=$RUN&sort=created_asc")"

echo; echo "-- 3. reorder: charlie before alpha, then bravo to position 0 --"
post "/api/v1/projects/$RUN-charlie/reorder" '{"beforeSlug":"'"$RUN"'-alpha"}'
post "/api/v1/projects/$RUN-bravo/reorder" '{"position":0}'
echo "manual       : $(get "/api/v1/projects?q=$RUN&sort=manual")"

echo; echo "-- 4. sort + filter + errors --"
echo "name_asc     : $(get "/api/v1/projects?q=$RUN&sort=name_asc")"
echo "q=RUN Bravo  : $(get "/api/v1/projects?q=$RUN%20Bravo")"
echo "bad sort     : $(get "/api/v1/projects?q=$RUN&sort=sideways")"
echo "two selectors: $(raw "/api/v1/projects/$RUN-alpha/reorder" '{"position":0,"afterSlug":"x"}')"

echo; echo "-- 5. unpin alpha --"
post "/api/v1/projects/$RUN-alpha/unpin"
echo "manual       : $(get "/api/v1/projects?q=$RUN&sort=manual")"

echo; echo "-- 6. three documents in the alpha project --"
for t in Zulu Mike alpha; do
  curl -s -o /dev/null -X POST "$BASE/api/v1/projects/$RUN-alpha/documents" "${AUTH[@]}" \
    -H 'Content-Type: application/json' -d "{\"title\":\"Doc $t\",\"content\":\"body of $t for 184\"}"
done
echo "default      : $(get "/api/v1/projects/$RUN-alpha/documents")"

echo; echo "-- 7. pin doc-zulu, reorder doc-mike, sort/filter --"
post "/api/v1/projects/$RUN-alpha/documents/doc-zulu/pin"
post "/api/v1/projects/$RUN-alpha/documents/doc-mike/reorder" '{"position":0}'
echo "manual       : $(get "/api/v1/projects/$RUN-alpha/documents?sort=manual")"
echo "name_asc     : $(get "/api/v1/projects/$RUN-alpha/documents?sort=name_asc")"
echo "q=184        : $(get "/api/v1/projects/$RUN-alpha/documents?q=184")"
echo "q=Doc Mike   : $(get "/api/v1/projects/$RUN-alpha/documents?q=Doc%20Mike")"
echo "bad sort     : $(get "/api/v1/projects/$RUN-alpha/documents?sort=sideways")"
echo "empty body   : $(raw "/api/v1/projects/$RUN-alpha/documents/doc-mike/reorder" '{}')"

echo; echo "-- 8. cleanup --"
for s in alpha bravo charlie; do
  curl -s -o /dev/null -X DELETE "$BASE/api/v1/projects/$RUN-$s" "${AUTH[@]}"
done
echo "remaining    : $(get "/api/v1/projects?q=$RUN")"
rm -f "$FMT"
echo "== done =="
