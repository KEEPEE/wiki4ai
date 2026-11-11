#!/bin/sh
# ============================================================================
# WIKI4AI-178  ci/test-prune-stale-images.sh
#
# Offline test for ci/prune-stale-images.sh.  No docker, no network, no SSH, no
# server: `docker` and `df` are replaced by mock binaries in a throwaway
# directory that is put first on PATH.
#
#   sh ci/test-prune-stale-images.sh        -> exit 0 when every assertion holds
#
# The script under test can be overridden, which is how the non-vacuity proof is
# reproduced by hand outside the test:
#
#   sed '/# CONTAINER-PROTECTION/d' ci/prune-stale-images.sh > /tmp/mutant.sh
#   W4AI_PRUNE_SCRIPT=/tmp/mutant.sh sh ci/test-prune-stale-images.sh   -> FAILS
#
# This test is POSIX sh as well, because it must be runnable with plain `sh`.
# ============================================================================

set -u

LC_ALL=C
export LC_ALL

TEST_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
SUT=${W4AI_PRUNE_SCRIPT:-$TEST_DIR/prune-stale-images.sh}

if [ ! -f "$SUT" ]; then
  printf 'FATAL: script under test not found: %s\n' "$SUT" >&2
  exit 1
fi

WORK=$(mktemp -d 2>/dev/null) || WORK=""
if [ -z "$WORK" ] || [ ! -d "$WORK" ]; then
  WORK="/tmp/w4ai-prune-test.$$"
  mkdir -p "$WORK" || { printf 'FATAL: no temporary directory\n' >&2; exit 1; }
fi
trap 'rm -rf "$WORK"' EXIT INT TERM
mkdir -p "$WORK/bin"

NS=git.keepee.duckdns.org/services/wiki4ai
DEPLOY_TAG=83e0eb32
FAILURES=0
CHECKS=0

pass() { CHECKS=$((CHECKS + 1)); printf '  ok    %s\n' "$1"; }
bad()  { FAILURES=$((FAILURES + 1)); printf '  FAIL  %s\n' "$1"; }

# ---------------------------------------------------------------------------
# mock docker.
#
# It rejects every subcommand it does not know, so `docker image prune`,
# `docker system prune`, `docker volume rm`, `docker compose ...` would all
# exit 127 here. A regression that reaches for prune is caught by the mock.
# ---------------------------------------------------------------------------
cat >"$WORK/bin/docker" <<'MOCKEOF'
#!/bin/sh
printf '%s\n' "docker $*" >>"$MOCK_ARGV"
case $1 in
  ps)
    if [ "${MOCK_PS_EXIT:-0}" -ne 0 ]; then
      printf 'Cannot connect to the Docker daemon at unix:///var/run/docker.sock. Is the docker daemon running?\n' >&2
      exit "$MOCK_PS_EXIT"
    fi
    cat "$MOCK_PS"
    ;;
  images)
    if [ "${MOCK_IMAGES_EXIT:-0}" -ne 0 ]; then
      printf 'mock docker images failure\n' >&2
      exit "$MOCK_IMAGES_EXIT"
    fi
    cat "$MOCK_IMAGES"
    ;;
  rmi)
    shift
    printf '%s\n' "$*" >>"$MOCK_RMI"
    case " ${MOCK_RMI_FAIL:-} " in
      *" $1 "*)
        printf 'Error response from daemon: conflict: unable to remove repository reference "%s" (must force)\n' "$1" >&2
        exit 1
        ;;
    esac
    printf 'Untagged: %s\n' "$1"
    ;;
  *)
    printf 'mock docker: subcommand not allowed by WIKI4AI-178: %s\n' "$*" >&2
    exit 127
    ;;
esac
MOCKEOF

cat >"$WORK/bin/df" <<'MOCKEOF'
#!/bin/sh
printf '%s\n' "df $*" >>"$MOCK_ARGV"
printf 'Filesystem      Size  Used Avail Use%% Mounted on\n'
printf '%s\n' "$MOCK_DF"
MOCKEOF

chmod 755 "$WORK/bin/docker" "$WORK/bin/df"

PATH="$WORK/bin:$PATH"
export PATH

export MOCK_PS="$WORK/ps.txt"
export MOCK_IMAGES="$WORK/images.txt"
export MOCK_RMI="$WORK/rmi.txt"
export MOCK_ARGV="$WORK/argv.txt"

# ---------------------------------------------------------------------------
# Scenario data.
#
# The container-referenced tags are deliberately the OLDEST tag of their own
# repository, and the deploy tag is deliberately the OLDEST embedding tag.
# Retention alone would delete every one of them; only the protection rules can
# save them. That is what makes those assertions non-vacuous.
#
#   backend   83e0eb32 0188a3e0 60faa330 82097c60 latest <none>
#   frontend  83e0eb32 0188a3e0 60faa330 82097c60
#   mcp       83e0eb32 0188a3e0 60faa330 82097c60
#   embedding 0188a3e0 60faa330 82097c60 83e0eb32   <- deploy tag is the OLDEST
#   foreign   pgvector/pgvector:pg16  yuzutech/kroki:0.32.4
#             git.keepee.duckdns.org/services/otherproject/backend:deadbeef
#             postgres:16-alpine
# ---------------------------------------------------------------------------
write_images() {
  cat >"$WORK/images.txt" <<EOF
2026-10-11 10:00:00 +0200 CEST|$NS/backend|83e0eb32|sha256:aa01000000000000000000000000000000000000000000000000000000000001|@sha256:dd01000000000000000000000000000000000000000000000000000000000001|667MB
2026-10-10 10:00:00 +0200 CEST|$NS/backend|0188a3e0|sha256:aa02000000000000000000000000000000000000000000000000000000000002|@sha256:dd02000000000000000000000000000000000000000000000000000000000002|667MB
2026-10-09 10:00:00 +0200 CEST|$NS/backend|60faa330|sha256:aa03000000000000000000000000000000000000000000000000000000000003|@sha256:dd03000000000000000000000000000000000000000000000000000000000003|667MB
2026-10-08 10:00:00 +0200 CEST|$NS/backend|82097c60|sha256:aa04000000000000000000000000000000000000000000000000000000000004|@sha256:dd04000000000000000000000000000000000000000000000000000000000004|667MB
2026-10-07 10:00:00 +0200 CEST|$NS/backend|latest|sha256:aa06000000000000000000000000000000000000000000000000000000000006|@sha256:dd06000000000000000000000000000000000000000000000000000000000006|667MB
2026-10-06 10:00:00 +0200 CEST|$NS/backend|<none>|sha256:aa05000000000000000000000000000000000000000000000000000000000005|@sha256:dd05000000000000000000000000000000000000000000000000000000000005|667MB
2026-10-11 10:01:00 +0200 CEST|$NS/frontend|83e0eb32|sha256:bb01000000000000000000000000000000000000000000000000000000000001|@sha256:ee01000000000000000000000000000000000000000000000000000000000001|116MB
2026-10-10 10:01:00 +0200 CEST|$NS/frontend|0188a3e0|sha256:bb02000000000000000000000000000000000000000000000000000000000002|@sha256:ee02000000000000000000000000000000000000000000000000000000000002|116MB
2026-10-09 10:01:00 +0200 CEST|$NS/frontend|60faa330|sha256:bb03000000000000000000000000000000000000000000000000000000000003|@sha256:ee03000000000000000000000000000000000000000000000000000000000003|116MB
2026-10-08 10:01:00 +0200 CEST|$NS/frontend|82097c60|sha256:bb04000000000000000000000000000000000000000000000000000000000004|@sha256:ee04000000000000000000000000000000000000000000000000000000000004|116MB
2026-10-11 10:02:00 +0200 CEST|$NS/mcp|83e0eb32|sha256:cc01000000000000000000000000000000000000000000000000000000000001|@sha256:ff01000000000000000000000000000000000000000000000000000000000001|318MB
2026-10-10 10:02:00 +0200 CEST|$NS/mcp|0188a3e0|sha256:cc02000000000000000000000000000000000000000000000000000000000002|@sha256:ff02000000000000000000000000000000000000000000000000000000000002|318MB
2026-10-09 10:02:00 +0200 CEST|$NS/mcp|60faa330|sha256:cc03000000000000000000000000000000000000000000000000000000000003|@sha256:ff03000000000000000000000000000000000000000000000000000000000003|318MB
2026-10-08 10:02:00 +0200 CEST|$NS/mcp|82097c60|sha256:cc04000000000000000000000000000000000000000000000000000000000004|@sha256:ff04000000000000000000000000000000000000000000000000000000000004|318MB
2026-10-10 10:03:00 +0200 CEST|$NS/embedding|0188a3e0|sha256:cc11000000000000000000000000000000000000000000000000000000000001|@sha256:ff11000000000000000000000000000000000000000000000000000000000001|1.57GB
2026-10-09 10:03:00 +0200 CEST|$NS/embedding|60faa330|sha256:cc12000000000000000000000000000000000000000000000000000000000002|@sha256:ff12000000000000000000000000000000000000000000000000000000000002|1.57GB
2026-10-08 10:03:00 +0200 CEST|$NS/embedding|82097c60|sha256:cc13000000000000000000000000000000000000000000000000000000000003|@sha256:ff13000000000000000000000000000000000000000000000000000000000003|1.57GB
2026-10-07 10:03:00 +0200 CEST|$NS/embedding|83e0eb32|sha256:cc14000000000000000000000000000000000000000000000000000000000004|@sha256:ff14000000000000000000000000000000000000000000000000000000000004|1.57GB
2026-01-01 00:00:00 +0100 CET|pgvector/pgvector|pg16|sha256:ff21000000000000000000000000000000000000000000000000000000000001|@sha256:ff21000000000000000000000000000000000000000000000000000000000001|500MB
2026-01-01 00:00:00 +0100 CET|yuzutech/kroki|0.32.4|sha256:ff22000000000000000000000000000000000000000000000000000000000001|@sha256:ff22000000000000000000000000000000000000000000000000000000000001|300MB
2026-01-01 00:00:00 +0100 CET|git.keepee.duckdns.org/services/otherproject/backend|deadbeef|sha256:ff23000000000000000000000000000000000000000000000000000000000001|@sha256:ff23000000000000000000000000000000000000000000000000000000000001|100MB
2026-01-01 00:00:00 +0100 CET|postgres|16-alpine|sha256:ff24000000000000000000000000000000000000000000000000000000000001|@sha256:ff24000000000000000000000000000000000000000000000000000000000001|400MB
EOF
}

# backend:82097c60 -> STOPPED container, matched by repo:tag
# mcp:82097c60     -> RUNNING container, matched by DIGEST (the containerd image
#                    store reports a digest, so a tag-only match would miss it)
write_ps() {
  cat >"$WORK/ps.txt" <<EOF
$NS/backend:82097c60
$NS/mcp@sha256:ff04000000000000000000000000000000000000000000000000000000000004
$NS/frontend:83e0eb32
pgvector/pgvector:pg16
yuzutech/kroki:0.32.4
postgres:16-alpine
EOF
}

reset_env() {
  write_images
  write_ps
  : >"$WORK/rmi.txt"
  : >"$WORK/argv.txt"
  MOCK_PS_EXIT=0;    export MOCK_PS_EXIT
  MOCK_IMAGES_EXIT=0; export MOCK_IMAGES_EXIT
  MOCK_RMI_FAIL="";  export MOCK_RMI_FAIL
  IMAGE_RETENTION=3; export IMAGE_RETENTION
  MOCK_DF="mockfs      218G  174G   34G  84% /"; export MOCK_DF
}

run() { # script-under-test, then its arguments
  sut=$1
  shift
  "$sut" "$@" >"$WORK/out.txt" 2>&1
  RC=$?
}

assert_rc() { # want rc label
  if [ "$2" -eq "$1" ]; then pass "$3 (exit $1)"; else bad "$3: expected exit $1, got $2"; fi
}

assert_has() { # label fixed-string
  if grep -F -q -- "$2" "$WORK/out.txt"; then pass "$1"; else
    bad "$1: expected the output to contain [$2] but it was absent"
  fi
}

assert_lacks() { # label fixed-string
  if grep -F -q -- "$2" "$WORK/out.txt"; then
    bad "$1: the output contains [$2] - that image must never be deleted"
  else pass "$1"; fi
}

assert_rmi_lacks() { # label fixed-string
  if grep -F -q -- "$2" "$WORK/rmi.txt"; then
    bad "$1: docker rmi was called with [$2]"
  else pass "$1"; fi
}

assert_rmi_is() { # label  file-of-expected-refs
  sort <"$WORK/rmi.txt" >"$WORK/rmi.actual"
  sort <"$2" >"$WORK/rmi.want"
  if diff "$WORK/rmi.want" "$WORK/rmi.actual" >"$WORK/rmi.diff" 2>&1; then
    pass "$1 ($(awk 'END { print NR }' "$WORK/rmi.want") tags deleted, exactly as expected)"
  else
    bad "$1: the set of deleted tags is WRONG"
    printf '        expected to be deleted:\n'; sed 's/^/          /' "$WORK/rmi.want"
    printf '        actually deleted:\n';       sed 's/^/          /' "$WORK/rmi.actual"
    printf '        difference:\n';             sed 's/^/          /' "$WORK/rmi.diff"
  fi
}

assert_rmi_empty() { # label
  if [ -s "$WORK/rmi.txt" ]; then
    bad "$1: docker rmi was called although nothing may be deleted:"
    sed 's/^/          /' "$WORK/rmi.txt"
  else pass "$1 (no docker rmi call at all)"; fi
}

# =========================================================================
printf '\n=== 0. static checks on the script under test ===\n'

# The header of the script under test NAMES the bash-isms it avoids, so comments
# are stripped before scanning; otherwise the scan reports the warning list as a
# usage. Patterns live in a file so that patterns containing a quote ( $'...' )
# can be expressed without shell-quoting gymnastics.
sed 's/#.*$//' "$SUT" >"$WORK/nocomments"
cat >"$WORK/bashisms.pat" <<'PATEOF'
\[\[[[:space:]]
<<<
mapfile
\+=
<\(
[[:space:]]=\(
&>
\|&
\$'
shopt
^[[:space:]]*function[[:space:]]
^[[:space:]]*declare[[:space:]]
^[[:space:]]*local[[:space:]]
^[[:space:]]*typeset[[:space:]]
^[[:space:]]*let[[:space:]]
^[[:space:]]*\(\(
PATEOF
if grep -nE -f "$WORK/bashisms.pat" "$WORK/nocomments" >"$WORK/bashisms.txt" 2>&1; then
  bad "POSIX scan: bash-isms found in $SUT"
  sed 's/^/          /' "$WORK/bashisms.txt"
else
  pass "POSIX scan: no [[ ]], <<<, mapfile, +=, <( ), =(, &>, |&, \$'...', shopt, function, declare, local, typeset, let, (( ))"
fi

cat >"$WORK/prune.pat" <<'PATEOF'
docker[^#]*prune
image[[:space:]]+prune
system[[:space:]]+prune
builder[[:space:]]+prune
container[[:space:]]+prune
volume[[:space:]]+prune
network[[:space:]]+prune
PATEOF
if grep -nE -f "$WORK/prune.pat" "$WORK/nocomments" >"$WORK/prunes.txt" 2>&1; then
  bad "prune scan: a docker prune command was found in $SUT"
  sed 's/^/          /' "$WORK/prunes.txt"
else
  pass "prune scan: the script never calls docker image/system/builder/volume prune"
fi

# =========================================================================
printf '\n=== 1. main scenario: 4 repositories x 4 tag sets, retention 3 ===\n'
reset_env
run "$SUT" prune "$DEPLOY_TAG" 3
printf '%s\n' "--- script output (verbatim) ---"
sed 's/^/  | /' "$WORK/out.txt"
printf '%s\n' "--------------------------------"

cat >"$WORK/expect-main.txt" <<EOF
$NS/backend:latest
$NS/frontend:82097c60
EOF

assert_rc 0 "$RC" "main scenario"
assert_rmi_is "main scenario deleted exactly the 2 unprotected stale tags" "$WORK/expect-main.txt"

assert_has "stale frontend tag was deleted"        "DELETE $NS/frontend:82097c60"
assert_has "stale backend latest tag was deleted"  "DELETE $NS/backend:latest"
assert_lacks "backend:82097c60 survived (STOPPED container references it)" "DELETE $NS/backend:82097c60"
assert_lacks "mcp:82097c60 survived (RUNNING container references it by digest)" "DELETE $NS/mcp:82097c60"
assert_lacks "embedding:83e0eb32 survived (deploy tag, although it is the oldest embedding tag)" "DELETE $NS/embedding:83e0eb32"
assert_lacks "backend:83e0eb32 survived (retention)" "DELETE $NS/backend:83e0eb32"
assert_lacks "backend:0188a3e0 survived (retention)" "DELETE $NS/backend:0188a3e0"
assert_lacks "backend:60faa330 survived (retention)" "DELETE $NS/backend:60faa330"
assert_lacks "frontend:83e0eb32 survived (running container + retention)" "DELETE $NS/frontend:83e0eb32"
assert_lacks "frontend:0188a3e0 survived (retention)" "DELETE $NS/frontend:0188a3e0"
assert_lacks "frontend:60faa330 survived (retention)" "DELETE $NS/frontend:60faa330"
assert_lacks "mcp:83e0eb32 survived (retention)" "DELETE $NS/mcp:83e0eb32"
assert_lacks "mcp:0188a3e0 survived (retention)" "DELETE $NS/mcp:0188a3e0"
assert_lacks "mcp:60faa330 survived (retention)" "DELETE $NS/mcp:60faa330"
assert_lacks "embedding:0188a3e0 survived (retention)" "DELETE $NS/embedding:0188a3e0"
assert_lacks "embedding:60faa330 survived (retention)" "DELETE $NS/embedding:60faa330"
assert_lacks "embedding:82097c60 survived (retention)" "DELETE $NS/embedding:82097c60"
assert_rmi_lacks "untagged wiki4ai image was NOT pruned" "<none>"
assert_lacks "foreign pgvector image untouched" "DELETE pgvector/pgvector:pg16"
assert_lacks "foreign yuzutech/kroki image untouched" "DELETE yuzutech/kroki:0.32.4"
assert_lacks "foreign postgres image untouched" "DELETE postgres:16-alpine"
assert_lacks "foreign project on the SAME registry host untouched" "DELETE git.keepee.duckdns.org/services/otherproject/backend:deadbeef"
assert_has "df reported before" "df before"
assert_has "df reported after"  "df after"
assert_has "freed GB reported"  "freed: 0.78 GB"
assert_has "considered count reported" "wiki4ai tagged images considered : 17"
assert_has "protected count reported" "protected (container or deploy tag): 3"
if grep -F -q -- "prune" "$WORK/argv.txt"; then
  bad "runtime proof: the script invoked a docker prune subcommand"
else
  pass "runtime proof: no docker prune subcommand was ever called"
fi

# =========================================================================
printf '\n=== 2. IMAGE_RETENTION is configurable (N = 1) ===\n'
reset_env
run "$SUT" prune "$DEPLOY_TAG" 1
cat >"$WORK/expect-n1.txt" <<EOF
$NS/backend:0188a3e0
$NS/backend:60faa330
$NS/backend:latest
$NS/frontend:0188a3e0
$NS/frontend:60faa330
$NS/frontend:82097c60
$NS/mcp:0188a3e0
$NS/mcp:60faa330
$NS/embedding:60faa330
$NS/embedding:82097c60
EOF
assert_rc 0 "$RC" "retention 1"
assert_rmi_is "retention 1 deletes 10 tags and still protects container + deploy tag" "$WORK/expect-n1.txt"
assert_lacks "retention 1 still protects the stopped-container tag" "DELETE $NS/backend:82097c60"
assert_lacks "retention 1 still protects the digest-referenced tag" "DELETE $NS/mcp:82097c60"
assert_lacks "retention 1 still protects the deploy tag" "DELETE $NS/embedding:83e0eb32"

# =========================================================================
printf '\n=== 3. report mode deletes nothing ===\n'
reset_env
run "$SUT" report
assert_rc 0 "$RC" "report mode"
assert_rmi_empty "report mode"
assert_has "report mode says so" "report mode: no image was examined, nothing was deleted"

# =========================================================================
printf '\n=== 4. docker ps -a FAILS -> refuse to delete anything ===\n'
reset_env
MOCK_PS_EXIT=1; export MOCK_PS_EXIT
run "$SUT" prune "$DEPLOY_TAG" 3
assert_rc 4 "$RC" "docker ps -a failure"
assert_rmi_empty "docker ps -a failure"
assert_has "refusal is explained" "docker ps -a FAILED"
assert_has "refusal states nothing is deleted" "NOTHING is deleted"

# =========================================================================
printf '\n=== 5. docker ps -a returns an EMPTY list -> refuse ===\n'
reset_env
: >"$WORK/ps.txt"
run "$SUT" prune "$DEPLOY_TAG" 3
assert_rc 4 "$RC" "empty container list"
assert_rmi_empty "docker ps -a empty list"
assert_has "empty list refusal is explained" "returned an EMPTY list"

# =========================================================================
printf '\n=== 6. docker images FAILS -> refuse ===\n'
reset_env
MOCK_IMAGES_EXIT=1; export MOCK_IMAGES_EXIT
run "$SUT" prune "$DEPLOY_TAG" 3
assert_rc 4 "$RC" "docker images failure"
assert_rmi_empty "docker images failure"

# =========================================================================
printf '\n=== 7. a container references an image by SHORT ID, not by tag ===\n'
reset_env
cat >"$WORK/ps.txt" <<EOF
$NS/backend:82097c60
cc0400000000
$NS/frontend:83e0eb32
postgres:16-alpine
EOF
run "$SUT" prune "$DEPLOY_TAG" 3
assert_rc 0 "$RC" "short-id scenario"
assert_rmi_is "short-id reference protects mcp:82097c60" "$WORK/expect-main.txt"
assert_lacks "mcp:82097c60 protected via image ID prefix, not via any tag" "DELETE $NS/mcp:82097c60"

# =========================================================================
printf '\n=== 8. a failing docker rmi does not abort the cleanup ===\n'
reset_env
MOCK_RMI_FAIL="$NS/frontend:82097c60"; export MOCK_RMI_FAIL
run "$SUT" prune "$DEPLOY_TAG" 3
assert_rc 0 "$RC" "cleanup survives a failing docker rmi"
assert_has "failing rmi is reported as a warning" "docker rmi $NS/frontend:82097c60 failed"
assert_has "the other tag was still deleted" "deleted: 1"
assert_has "the failure is counted" "docker rmi failures: 1"

# =========================================================================
printf '\n=== 9. creation time unparseable -> fall back to docker order ===\n'
reset_env
sed 's/^[^|]*|/<unknown>|/' "$WORK/images.txt" >"$WORK/images.tmp"
mv "$WORK/images.tmp" "$WORK/images.txt"
run "$SUT" prune "$DEPLOY_TAG" 3
assert_rc 0 "$RC" "unparseable creation time"
assert_has "the fallback is announced" "no parseable creation time"
assert_rmi_is "fallback order still deletes exactly the same 2 tags" "$WORK/expect-main.txt"

# =========================================================================
printf '\n=== 10. loud warning when the disk is over the threshold ===\n'
reset_env
MOCK_DF="mockfs      218G  208G    0G 100% /"; export MOCK_DF
run "$SUT" prune "$DEPLOY_TAG" 3
assert_has "a 100% full disk produces a loud warning" "WARNING: / is 100% FULL"
assert_has "the warning names the incident" "WIKI4AI-178 failure mode"
reset_env
run "$SUT" prune "$DEPLOY_TAG" 3
if grep -F -q -- "WARNING: / is 84% FULL" "$WORK/out.txt"; then
  bad "an 84% disk must NOT trigger the >90% warning"
else
  pass "an 84% disk does not trigger the warning"
fi

# =========================================================================
printf '\n=== 11. NON-VACUITY: mutation checks ===\n'
# A test that cannot fail is worthless. These checks break each protection rule
# in a throwaway copy of the script and require the test to NOTICE it.
sed '/# CONTAINER-PROTECTION/d' "$SUT" >"$WORK/mutant-container.sh"
chmod 755 "$WORK/mutant-container.sh"
if cmp -s "$SUT" "$WORK/mutant-container.sh"; then
  bad "mutation 1 changed nothing: the CONTAINER-PROTECTION markers are missing from $SUT"
else
  pass "mutation 1 produced a modified copy (container protection removed)"
fi
reset_env
run "$WORK/mutant-container.sh" prune "$DEPLOY_TAG" 3
assert_rc 0 "$RC" "mutant without container protection still exits 0"
assert_has "MUTANT DELETES backend:82097c60 - the real test would have caught it" "DELETE $NS/backend:82097c60"
assert_has "MUTANT DELETES mcp:82097c60 - the real test would have caught it" "DELETE $NS/mcp:82097c60"
assert_lacks "mutant must still protect the deploy tag" "DELETE $NS/embedding:83e0eb32"

sed '/# DEPLOY-TAG-PROTECTION/d' "$SUT" >"$WORK/mutant-tag.sh"
chmod 755 "$WORK/mutant-tag.sh"
if cmp -s "$SUT" "$WORK/mutant-tag.sh"; then
  bad "mutation 2 changed nothing: the DEPLOY-TAG-PROTECTION marker is missing from $SUT"
else
  pass "mutation 2 produced a modified copy (deploy-tag protection removed)"
fi
reset_env
run "$WORK/mutant-tag.sh" prune "$DEPLOY_TAG" 3
assert_has "MUTANT DELETES the deploy tag embedding:83e0eb32 - the real test would have caught it" "DELETE $NS/embedding:83e0eb32"

# =========================================================================
printf '\n=== result ===\n'
printf 'assertions run    : %s\n' "$CHECKS"
printf 'assertions failed : %s\n' "$FAILURES"
if [ "$FAILURES" -gt 0 ]; then
  printf 'TEST FAILED\n'
  exit 1
fi
printf 'TEST PASSED\n'
exit 0
