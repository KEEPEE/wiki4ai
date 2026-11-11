#!/bin/sh
# ============================================================================
# WIKI4AI-178  ci/prune-stale-images.sh
#
# Reclaim disk on the deploy target by deleting STALE wiki4ai image TAGS.
# Cleanup is done BY TAG. It is never done by dangling-ness, and it never
# touches another project's images.
#
# HOW THE CI JOB INVOKES IT (piped over SSH, no file is transferred):
#
#   $DEPLOY_SSH "sh -s report"                              < ci/prune-stale-images.sh
#   $DEPLOY_SSH "sh -s prune <CI_COMMIT_SHORT_SHA> <N>"    < ci/prune-stale-images.sh
#
#   $1 = mode            : report (df only) | prune (default)
#   $2 = deploy tag      : the tag of the pipeline that is deploying. NEVER deleted.
#   $3 = IMAGE_RETENTION : how many NEWEST tags per repository to keep (default 3)
#
#   Environment overrides (all optional):
#     IMAGE_NAMESPACE  default git.keepee.duckdns.org/services/wiki4ai
#     IMAGE_RETENTION  default 3
#     DISK_PATH        default /
#     DISK_WARN_PCT    default 90
#
# ---------------------------------------------------------------------------
# WHY `docker image prune` IS THE WRONG TOOL HERE - DO NOT "SIMPLIFY" THIS TO IT
# ---------------------------------------------------------------------------
# Observed twice on 192.168.77.219: `docker system df` reported 55.5 GB
# reclaimable while `docker image prune` found 0 (later 1) dangling images and
# cheerfully concluded there was nothing to clean up. Both statements were true
# at the same time.
#
# Reason: Docker 29.1.3 on that host uses the containerd image store, which
# lives on / at /var/lib/containerd - NOT under `Docker Root Dir:
# /mnt/data/docker`. `docker info` therefore advertises a data-root that is not
# where the 91 GB actually sits, and `prune` only ever looks at UNTAGGED
# (dangling) images. The bloat here is TAGGED stale builds: 12 tags = 3 sets x
# 2.67 GB. prune cannot see a single one of them.
#
# So this script deletes by tag with `docker rmi <repo>:<tag>` and nothing else.
#
# ---------------------------------------------------------------------------
# WHAT IS NEVER TOUCHED (absolute rules)
# ---------------------------------------------------------------------------
#   * the tag of the pipeline that is deploying ($2)
#   * any image referenced by ANY container - RUNNING OR STOPPED. Containers
#     are read from `docker ps -a` and matched by repo:tag, by image ID and by
#     digest, because the containerd store may report a digest instead of a tag.
#   * images of other projects: anything outside IMAGE_NAMESPACE is never even
#     parsed (pgvector/pgvector, yuzutech/kroki, postgres, ...). They are not
#     ours and must not be touched.
#   * untagged / dangling images: that is prune territory and deliberately not
#     ours - removing an untagged image can strip layers a kept tag shares.
#   * volumes, containers, compose files, dockge: this script only ever runs
#     `docker images`, `docker ps -a` and `docker rmi`. Nothing else. No prune,
#     no system df mutation, no compose command, no volume, no rm of any file.
#
# RETENTION IS PER REPOSITORY, not global. backend is counted separately from
# embedding. A global N would let one 1.57 GB embedding image push frontend tags
# out of the kept window.
#
# WHY THE DEFAULT IS N = 3 AND NOT 5
#   One pipeline = one set = 2.67 GB (backend 667MB + embedding 1.57GB +
#   frontend 116MB + mcp 318MB). Three sets = 8.01 GB permanently held. On
#   .219 the root filesystem has 34 GB free and is already at 84 %, so a default
#   of 5 would permanently reserve 13.35 GB and leave the disk one bad day away
#   from the exact failure this ticket fixes. N = 3 keeps the current set plus
#   the two previous builds, which is enough to roll back. N is configurable via
#   IMAGE_RETENTION.
#
# `latest` is deliberately NOT special-cased: every build re-pushes it, the
# dockge stack never references it (the compose file is re-pointed to the commit
# SHA), so a stale `latest` left on the server is exactly the reclaimable bloat
# this job exists to remove.
#
# ---------------------------------------------------------------------------
# SHELL: STRICT POSIX sh ONLY
# ---------------------------------------------------------------------------
# This script is piped into shells we do not control:
#   * the deploy user's /bin/sh on the target host
#   * BusyBox ash, if it is ever run from the alpine job container
# Both are POSIX shells, not bash. A bash-ism fails silently or in the wrong
# place on BusyBox, and it cannot be noticed locally because the developer's own
# interactive shell is bash. Deliberately NOT used anywhere in this file:
#   arrays `x=(...)`        `[[ ... ]]`        `(( ... ))` arithmetic
#   here-strings `<<<`      `mapfile`          `+=` append
#   `function` keyword      process substitution `<(...)`
#   `$'...'` quoting        `local`            `declare`        `shopt`
#   `&>` / `|&` redirections
# Also avoided: GNU-only awk (gensub, length(array), delete array), GNU-only
# sort/awk options, and `mktemp` without a fallback.
# Verified with `sh -n` (dash), `bash -n`, and the static scan in
# ci/test-prune-stale-images.sh.
# ============================================================================

set -u

# Deterministic byte ordering for sort/grep/cut regardless of the login locale.
LC_ALL=C
export LC_ALL

log()  { printf '%s\n' "[w4ai-prune] $*"; }
warn() { printf '%s\n' "[w4ai-prune] WARN: $*"; }
fail() { printf '%s\n' "[w4ai-prune] ERROR: $*"; }

# --- configuration ----------------------------------------------------------
MODE=${1:-prune}
CURRENT_TAG=${2:-}
RETENTION=${3:-}
[ -n "$RETENTION" ] || RETENTION=${IMAGE_RETENTION:-3}
# The tag and the registry are also accepted from the environment, so the script
# still behaves correctly if it is invoked as a bare `sh -s` with the CI variables
# exported into the remote shell instead of passed as positional arguments.
[ -n "$CURRENT_TAG" ] || CURRENT_TAG=${CI_COMMIT_SHORT_SHA:-}
IMAGE_NAMESPACE=${IMAGE_NAMESPACE:-${IMAGE_REGISTRY:-git.keepee.duckdns.org/services/wiki4ai}}
DISK_PATH=${DISK_PATH:-/}
DISK_WARN_PCT=${DISK_WARN_PCT:-90}

case $MODE in
  report|prune) ;;
  *) fail "unknown mode '$MODE' (expected 'report' or 'prune')"; exit 2 ;;
esac

case $RETENTION in
  ''|*[!0-9]*)
    fail "IMAGE_RETENTION must be a positive integer, got: $RETENTION"
    exit 2
    ;;
esac
if [ "$RETENTION" -lt 1 ]; then
  fail "IMAGE_RETENTION must be at least 1, got: $RETENTION"
  exit 2
fi

if [ -n "$CURRENT_TAG" ]; then
  # The tag is interpolated into nothing but a comparison, but refusing anything
  # that is not tag-shaped keeps a malformed CI variable from doing damage.
  case $CURRENT_TAG in
    *[!A-Za-z0-9._-]*)
      fail "deploy tag contains unsupported characters: $CURRENT_TAG"
      exit 2
      ;;
  esac
else
  warn "no deploy tag was passed: protection of the tag being deployed is INACTIVE."
  warn "Container protection still shields every image a container references."
fi

# A non-interactive `sh -s` over SSH does not source the profile, so make sure
# the usual locations are searched before concluding that docker is missing.
PATH="$PATH:/usr/local/bin:/usr/bin:/usr/sbin:/bin"
export PATH

WORK=$(mktemp -d 2>/dev/null) || WORK=""
if [ -z "$WORK" ] || [ ! -d "$WORK" ]; then
  WORK="/tmp/w4ai-prune.$$"
  mkdir -p "$WORK" || { fail "cannot create a temporary work directory"; exit 1; }
fi
trap 'rm -rf "$WORK"' EXIT INT TERM

SERVER_HOSTNAME=$(uname -n 2>/dev/null || echo "unknown host")

# --- disk reporting ---------------------------------------------------------
# `df -P` is the POSIX portable output: exactly one line per filesystem, never
# wrapped, so field 5 is reliably the Use% column.
disk_table() {
  df -hP "$DISK_PATH" 2>&1
}

disk_use_pct() {
  df -hP "$DISK_PATH" 2>/dev/null | awk 'NR == 2 { v = $5; sub(/%$/, "", v); print v }'
}

disk_report() {
  disk_table | sed 's/^/    /'
}

check_disk() {
  stage=$1
  pct=$(disk_use_pct)
  case $pct in
    ''|*[!0-9]*)
      warn "could not read the usage percentage of $DISK_PATH"
      return 0
      ;;
  esac
  log "disk $stage: $DISK_PATH is ${pct}% used"
  if [ "$pct" -gt "$DISK_WARN_PCT" ]; then
    printf '%s\n' \
      "############################################################" \
      "###  WARNING: $DISK_PATH is ${pct}% FULL on ${SERVER_HOSTNAME}" \
      "###  Alert threshold is ${DISK_WARN_PCT}%. A single pipeline pulls ~2.7 GB." \
      "###  This is the WIKI4AI-178 failure mode: 'no space left on device'." \
      "############################################################"
  fi
}

log "=== WIKI4AI-178 wiki4ai image-tag cleanup ==="
log "mode=$MODE  namespace=$IMAGE_NAMESPACE  retention=$RETENTION  deploy-tag=${CURRENT_TAG:-<not supplied>}"
log "df before:"
disk_report
check_disk before

if [ "$MODE" = report ]; then
  log "report mode: no image was examined, nothing was deleted"
  exit 0
fi

if ! command -v docker >/dev/null 2>&1; then
  fail "docker is not on PATH; nothing was deleted"
  exit 3
fi

# --- containers first: they define what must NEVER be deleted ---------------
if ! docker ps -a --format '{{.Image}}' >"$WORK/ps.raw" 2>"$WORK/ps.err"; then
  warn "docker ps -a FAILED. Without container data there is no way to tell a"
  warn "live image from a stale one, so NOTHING is deleted (WIKI4AI-178)."
  sed 's/^/    /' "$WORK/ps.err"
  exit 4
fi
sed '/^[[:space:]]*$/d' "$WORK/ps.raw" >"$WORK/ps"
if [ ! -s "$WORK/ps" ]; then
  warn "docker ps -a returned an EMPTY list. On a host that is supposed to run the"
  warn "wiki4ai stack that means the daemon did not answer properly. Deleting tags"
  warn "without knowing what a container references is exactly how a live image gets"
  warn "removed, so NOTHING is deleted (WIKI4AI-178)."
  exit 4
fi

# --- images ----------------------------------------------------------------
# '|' is used as the field separator because it cannot appear in a repository
# name, a tag, an image ID, a digest or a size. A literal tab cannot be relied
# on inside a --format string, so it is not used.
IMG_FMT='{{.CreatedAt}}|{{.Repository}}|{{.Tag}}|{{.ID}}|{{.Digest}}|{{.Size}}'
if ! docker images --format "$IMG_FMT" >"$WORK/all.raw" 2>"$WORK/img.err"; then
  warn "docker images FAILED; nothing was deleted (WIKI4AI-178)"
  sed 's/^/    /' "$WORK/img.err"
  exit 4
fi
sed '/^[[:space:]]*$/d' "$WORK/all.raw" >"$WORK/all"

# Only our own namespace. Everything else on the host is not ours and is never
# parsed, never selected and never deleted.
awk -F'|' -v ns="$IMAGE_NAMESPACE" \
  'NF >= 6 && index($2, ns "/") == 1 && $3 != "" && $3 != "<none>"' \
  "$WORK/all" >"$WORK/ours"

awk -F'|' -v ns="$IMAGE_NAMESPACE" \
  'NF >= 6 && index($2, ns "/") == 1 && ($3 == "" || $3 == "<none>")' \
  "$WORK/all" >"$WORK/untagged"

TOTAL_ALL=$(awk 'END { print NR }' "$WORK/all")
TOTAL_OURS=$(awk 'END { print NR }' "$WORK/ours")
TOTAL_UNTAGGED=$(awk 'END { print NR }' "$WORK/untagged")
log "images on host: $TOTAL_ALL total, $TOTAL_OURS wiki4ai tagged candidates,"
log "  $TOTAL_UNTAGGED wiki4ai untagged images (intentionally NOT touched - that is prune territory)"

: >"$WORK/candidates"
: >"$WORK/protected"
: >"$WORK/to_delete"

if [ "$TOTAL_OURS" -eq 0 ]; then
  log "no wiki4ai tagged image matched $IMAGE_NAMESPACE - nothing to do"
  log "df after:"
  disk_report
  check_disk after
  exit 0
fi

# --- per repository: keep the N newest, everything else is a candidate ------
cut -d'|' -f2 "$WORK/ours" | sort -u >"$WORK/repos"
while IFS= read -r repo; do
  awk -F'|' -v r="$repo" '$2 == r' "$WORK/ours" >"$WORK/repo"
  total=$(awk 'END { print NR }' "$WORK/repo")
  dated=$(awk -F'|' '$1 ~ /^[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9][ ]/' "$WORK/repo" | awk 'END { print NR }')
  if [ "$dated" -eq "$total" ]; then
    # Creation timestamps are YYYY-MM-DD HH:MM:SS +ZZZZ TZ, so a byte-wise
    # reverse sort is newest-first.
    sort -r -t'|' -k1,1 "$WORK/repo" >"$WORK/repo.sorted"
    order_note="creation time"
  else
    warn "$repo: $((total - dated)) of $total tags have no parseable creation time;"
    warn "  falling back to the order reported by docker images (newest first)."
    cat "$WORK/repo" >"$WORK/repo.sorted"
    order_note="docker report order"
  fi
  n=0
  kept=0
  stale=0
  while IFS= read -r row; do
    n=$((n + 1))
    if [ "$n" -le "$RETENTION" ]; then
      printf '%s\n' "$row" >>"$WORK/kept"
      kept=$((kept + 1))
    else
      printf '%s\n' "$row" >>"$WORK/candidates"
      stale=$((stale + 1))
    fi
  done <"$WORK/repo.sorted"
  log "repo $repo: $total tags, keeping $kept newest by $order_note, $stale stale candidate(s)"
done <"$WORK/repos"

# --- protection: never delete what a container or this pipeline references ---
# Every line below marked CONTAINER-PROTECTION is what keeps a live image alive.
# ci/test-prune-stale-images.sh deletes exactly those lines in a throwaway copy
# of this script to prove the test would notice.
awk -F'|' -v PSF="$WORK/ps" -v TAG="$CURRENT_TAG" -v KF="$WORK/protected" -v DF="$WORK/to_delete" '
BEGIN {
  while ((getline line < PSF) > 0) { if (line != "") ps[line] = 1 }
  close(PSF)
}
{
  repo = $2; tag = $3; id = $4; dig = $5
  dref = dig; sub(/^@/, "", dref)
  name = repo ":" tag
  ihex = id;  sub(/^sha256:/, "", ihex)
  if (ihex !~ /^[0-9a-f]+$/) ihex = ""
  dhex = dref; sub(/^sha256:/, "", dhex)
  if (dhex !~ /^[0-9a-f]+$/) dhex = ""

  if (TAG != "" && tag == TAG) { print $0 >> KF; next }   # DEPLOY-TAG-PROTECTION

  if (name in ps) { print $0 >> KF; next }                # CONTAINER-PROTECTION
  if (id in ps)   { print $0 >> KF; next }                # CONTAINER-PROTECTION
  if (dref != "" && dref != "<none>" && ((repo "@" dref) in ps)) { print $0 >> KF; next }   # CONTAINER-PROTECTION

  # A container may reference an image by a short id (untagged container) or by
  # a digest, so match hex prefixes against both the image id and the digest.
  for (p in ps) {
    ph = p; sub(/^@?sha256:/, "", ph)
    if (ph !~ /^[0-9a-f]+$/) continue                      # CONTAINER-PROTECTION
    if (ihex != "" && (index(ihex, ph) == 1 || index(ph, ihex) == 1)) { print $0 >> KF; next }   # CONTAINER-PROTECTION
    if (dhex != "" && (index(dhex, ph) == 1 || index(ph, dhex) == 1)) { print $0 >> KF; next }   # CONTAINER-PROTECTION
  }

  print $0 >> DF
}
' "$WORK/candidates"

# --- delete, one tag at a time, never forced --------------------------------
# No -f. `docker rmi` without -f refuses an image a container still references,
# which is a second safety net under the protection pass above. A failure here
# is logged and skipped: it must never become a deploy failure.
deleted=0
failed=0
freed=0
: >"$WORK/deleted"
while IFS='|' read -r created repo tag id dig size; do
  ref="$repo:$tag"
  log "DELETE $ref  size=$size  created=$created"
  if docker rmi "$ref" >"$WORK/rmi.out" 2>&1; then
    deleted=$((deleted + 1))
    printf '%s\n' "$ref" >>"$WORK/deleted"
    # POSIX sh arithmetic is integer-only, so the fractional sizes (1.57GB) are
    # summed with awk. Multipliers are 1000-based because that is how docker
    # formats the size it reports.
    freed=$(awk -v cur="$freed" -v sz="$size" 'BEGIN {
      n = sz + 0
      m = 1
      if (sz ~ /TB/) m = 1000000000000
      else if (sz ~ /GB/) m = 1000000000
      else if (sz ~ /MB/) m = 1000000
      else if (sz ~ /[kK][bB]/) m = 1000
      printf "%.0f", cur + n * m
    }')
  else
    failed=$((failed + 1))
    warn "docker rmi $ref failed - the image stays and the deploy is NOT affected:"
    sed 's/^/    /' "$WORK/rmi.out"
  fi
done <"$WORK/to_delete"

CANDIDATES=$(awk 'END { print NR }' "$WORK/to_delete")
PROTECTED=$(awk 'END { print NR }' "$WORK/protected")
KEPT=$(awk 'END { print NR }' "$WORK/kept")
FREED_GB=$(awk -v b="$freed" 'BEGIN { printf "%.2f", b / 1000000000 }')

log "summary:"
log "  wiki4ai tagged images considered : $TOTAL_OURS"
log "  kept by retention (newest $RETENTION per repo): $KEPT"
log "  stale candidates               : $CANDIDATES"
log "  protected (container or deploy tag): $PROTECTED"
log "  deleted: $deleted   docker rmi failures: $failed"
log "  freed: ${FREED_GB} GB (sum of the sizes docker reported for the removed tags;"
log "  an upper bound - layers shared with a kept tag are not counted twice by docker)"
log "df after:"
disk_report
check_disk after
exit 0
