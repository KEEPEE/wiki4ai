#!/bin/bash
# check_pipeline.sh - Check if GitLab pipeline has completed successfully
# Waits for pipeline to finish (max 45 minutes), then reports detailed results
# Usage: ./scripts/check_pipeline.sh
#
# Configuration via environment variables (no credentials in the repo):
#   GITLAB_URL   - GitLab instance base URL (required)
#   GITLAB_TOKEN - Personal access token with 'api' scope (required)
#   PROJECT_PATH - Project path used for the status link (default: wiki4ai)

GITLAB_URL="${GITLAB_URL:?Set GITLAB_URL to your GitLab instance URL}"
PROJECT_PATH="${PROJECT_PATH:-wiki4ai}"
API_TOKEN="${GITLAB_TOKEN:?Set GITLAB_TOKEN to a GitLab personal access token with api scope}"

# Timeout: 45 minutes = 2700 seconds
MAX_WAIT=2700
POLL_INTERVAL=30

PROJECT_ID=$(curl -s --header "PRIVATE-TOKEN: ${API_TOKEN}" "${GITLAB_URL}/api/v4/projects?search=wiki4ai&owned=true" | python3 -c "import sys, json; data = json.load(sys.stdin); print(data[0]['id'] if data else '')")

if [ -z "$PROJECT_ID" ]; then
    echo "❌ Project not found!"
    exit 1
fi

echo "📊 Pipeline Status for wiki4ai (Project ID: ${PROJECT_ID})"
echo "=============================================================="

# Get latest pipeline
PIPELINE=$(curl -s --header "PRIVATE-TOKEN: ${API_TOKEN}" "${GITLAB_URL}/api/v4/projects/${PROJECT_ID}/pipelines?per_page=1&order_by=id")

if [ -z "$PIPELINE" ] || echo "$PIPELINE" | python3 -c "import sys, json; data = json.load(sys.stdin); exit(0 if len(data) > 0 else 1)" 2>/dev/null; then
    PIPELINE_STATUS=$(echo "$PIPELINE" | python3 -c "import sys, json; data = json.load(sys.stdin); print(data[0]['status'])")
    PIPELINE_ID=$(echo "$PIPELINE" | python3 -c "import sys, json; data = json.load(sys.stdin); print(data[0]['id'])")
    PIPELINE_SHA=$(echo "$PIPELINE" | python3 -c "import sys, json; data = json.load(sys.stdin); print(data[0]['sha'][:8])")

    echo ""
    echo "Latest Pipeline:"
    echo "  ID:       ${PIPELINE_ID}"
    echo "  Commit:   ${PIPELINE_SHA}"
    echo "  Status:   $(echo "$PIPELINE_STATUS" | awk '{print toupper($0)}')"
    echo "  Created:  $(echo "$PIPELINE" | python3 -c "import sys, json; data = json.load(sys.stdin); print(data[0]['created_at'])")"

    # If pipeline is already finished (success/failed/canceled/skipped/manual), skip waiting
    if [[ "$PIPELINE_STATUS" == "success" || "$PIPELINE_STATUS" == "failed" || "$PIPELINE_STATUS" == "canceled" || "$PIPELINE_STATUS" == "skipped" || "$PIPELINE_STATUS" == "manual" ]]; then
        echo ""
        echo "Pipeline already finished. Checking results..."
    else
        # Wait for pipeline to finish or timeout
        echo ""
        echo "⏳ Pipeline is '${PIPELINE_STATUS}'. Waiting for completion (max ${MAX_WAIT}s)..."
        ELAPSED=0

        while [ $ELAPSED -lt $MAX_WAIT ]; do
            sleep $POLL_INTERVAL
            ELAPSED=$((ELAPSED + POLL_INTERVAL))

            # Get current pipeline status
            CURRENT=$(curl -s --header "PRIVATE-TOKEN: ${API_TOKEN}" "${GITLAB_URL}/api/v4/projects/${PROJECT_ID}/pipelines/${PIPELINE_ID}")
            PIPELINE_STATUS=$(echo "$CURRENT" | python3 -c "import sys, json; print(json.load(sys.stdin)['status'])")

            echo "  ⏰ ${ELAPSED}s elapsed — Status: $(echo "$PIPELINE_STATUS" | awk '{print toupper($0)}')"

            if [[ "$PIPELINE_STATUS" == "success" || "$PIPELINE_STATUS" == "failed" || "$PIPELINE_STATUS" == "canceled" || "$PIPELINE_STATUS" == "skipped" || "$PIPELINE_STATUS" == "manual" ]]; then
                echo ""
                echo "✅ Pipeline finished after ${ELAPSED}s!"
                break
            fi
        done

        if [ $ELAPSED -ge $MAX_WAIT ] && [[ "$PIPELINE_STATUS" == "running" || "$PIPELINE_STATUS" == "pending" ]]; then
            echo ""
            echo "⚠️ TIMEOUT: Pipeline still '${PIPELINE_STATUS}' after ${MAX_WAIT}s (45 min)"
            PIPELINE_STATUS="timeout"
        fi
    fi

    # Get jobs for this pipeline
    JOBS=$(curl -s --header "PRIVATE-TOKEN: ${API_TOKEN}" "${GITLAB_URL}/api/v4/projects/${PROJECT_ID}/pipelines/${PIPELINE_ID}/jobs?per_page=100")

    echo ""
    echo "📋 Jobs:"
    echo "$JOBS" | python3 -c "
import sys, json
jobs = json.load(sys.stdin)
for job in jobs:
    status_icon = {'success': '✅', 'failed': '❌', 'running': '🔄', 'pending': '⏳', 'canceled': '⛔'}.get(job['status'], '❓')
    print(f\"  {status_icon} {job['name']:40s} [{job['status'].upper()}]\")
"

    # Handle skipped pipelines — no jobs to analyze, but not a failure
    if [[ "$PIPELINE_STATUS" == "skipped" ]]; then
        echo ""
        echo "⏭️ Pipeline was SKIPPED (no code changes detected that trigger the pipeline)."
        exit 0
    fi

    # Check if all jobs are successful (robust: use temp file to avoid pipe issues)
    TMPFILE=$(mktemp)
    echo "$JOBS" > "$TMPFILE"
    ALL_SUCCESS=$(python3 -c "
import json, sys
try:
    with open('$TMPFILE') as f:
        jobs = json.load(f)
    if not jobs:
        print('false')
    else:
        # Normalize to lowercase for bash comparison
        result = all(j['status'] == 'success' for j in jobs)
        print(str(result).lower())
except Exception as e:
    print('false', file=sys.stderr)
    print('false')
" 2>/dev/null)
    rm -f "$TMPFILE"

    if [ "$ALL_SUCCESS" = "true" ]; then
        echo ""
        echo "🎉 Pipeline completed successfully!"
        exit 0
    else
        # Detailed failure analysis
        FAILED_JOBS=$(echo "$JOBS" | python3 -c "import sys, json; jobs = json.load(sys.stdin); print([j for j in jobs if j['status'] == 'failed'])")

        echo ""
        echo "=============================================================="
        echo "❌ PIPELINE FAILURE — Detailed Analysis"
        echo "=============================================================="
        echo ""

        # Show failed job details with logs
        FAILED_COUNT=$(echo "$JOBS" | python3 -c "import sys, json; jobs = json.load(sys.stdin); print(sum(1 for j in jobs if j['status'] == 'failed'))")
        CANCELED_COUNT=$(echo "$JOBS" | python3 -c "import sys, json; jobs = json.load(sys.stdin); print(sum(1 for j in jobs if j['status'] == 'canceled'))")

        echo "Summary:"
        echo "  Failed:   ${FAILED_COUNT} job(s)"
        if [ "$CANCELED_COUNT" -gt 0 ]; then
            echo "  Canceled: ${CANCELED_COUNT} job(s)"
        fi
        echo ""

        # For each failed job, fetch the trace/log
        JOB_IDS=$(echo "$JOBS" | python3 -c "import sys, json; jobs = json.load(sys.stdin); print(','.join(str(j['id']) for j in jobs if j['status'] == 'failed'))")

        if [ -n "$JOB_IDS" ]; then
            IFS=',' read -ra JOB_ID_ARRAY <<< "$JOB_IDS"
            for JOB_ID in "${JOB_ID_ARRAY[@]}"; do
                # Get job details
                JOB_INFO=$(curl -s --header "PRIVATE-TOKEN: ${API_TOKEN}" "${GITLAB_URL}/api/v4/projects/${PROJECT_ID}/jobs/${JOB_ID}")

                JOB_NAME=$(echo "$JOB_INFO" | python3 -c "import sys, json; print(json.load(sys.stdin)['name'])")
                JOB_STAGE=$(echo "$JOB_INFO" | python3 -c "import sys, json; print(json.load(sys.stdin).get('stage', 'unknown'))")
                JOB_STARTED_AT=$(echo "$JOB_INFO" | python3 -c "import sys, json; print(json.load(sys.stdin).get('started_at', 'N/A'))")
                JOB_FINISHED_AT=$(echo "$JOB_INFO" | python3 -c "import sys, json; print(json.load(sys.stdin).get('finished_at', 'N/A'))")

                echo "--------------------------------------------------------------"
                echo "🔴 Failed Job: ${JOB_NAME}"
                echo "   Stage:      ${JOB_STAGE}"
                echo "   Started:    ${JOB_STARTED_AT}"
                echo "   Finished:   ${JOB_FINISHED_AT}"
                echo ""

                # Fetch job trace (log) — last 200 lines to keep it manageable
                TRACE=$(curl -s --header "PRIVATE-TOKEN: ${API_TOKEN}" "${GITLAB_URL}/api/v4/projects/${PROJECT_ID}/jobs/${JOB_ID}/trace")

                if [ -n "$TRACE" ]; then
                    # Show the tail of the log (last 150 lines) and any error patterns
                    LOG_LINES=$(echo "$TRACE" | wc -l)
                    echo "   Log (${LOG_LINES} lines, showing last 150):"

                    if [ "$LOG_LINES" -gt 150 ]; then
                        echo "   ... (first lines omitted for brevity) ..."
                        echo ""
                        echo "$TRACE" | tail -n 150 | while IFS= read -r line; do
                            # Highlight error/warning patterns
                            if echo "$line" | grep -qiE '(error|exception|failed|fatal|traceback|panic|segfault)'; then
                                echo "   >>> ${line}"
                            else
                                echo "       ${line}"
                            fi
                        done
                    else
                        echo "$TRACE" | while IFS= read -r line; do
                            if echo "$line" | grep -qiE '(error|exception|failed|fatal|traceback|panic|segfault)'; then
                                echo "   >>> ${line}"
                            else
                                echo "       ${line}"
                            fi
                        done
                    fi

                    # Extract key error messages for easy parsing by agent
                    ERROR_MSGS=$(echo "$TRACE" | grep -iE '(error|exception|failed|fatal)' | tail -5)
                    if [ -n "$ERROR_MSGS" ]; then
                        echo ""
                        echo "   Key errors:"
                        echo "$ERROR_MSGS" | while IFS= read -r line; do
                            echo "     • ${line}"
                        done
                    fi
                else
                    echo "   (No trace available)"
                fi

                echo ""
            done
        fi

        # Show canceled jobs too
        CANCELED_JOB_IDS=$(echo "$JOBS" | python3 -c "import sys, json; jobs = json.load(sys.stdin); print(','.join(str(j['id']) for j in jobs if j['status'] == 'canceled'))")
        if [ -n "$CANCELED_JOB_IDS" ]; then
            echo "--------------------------------------------------------------"
            echo "⛔ Canceled Jobs:"
            IFS=',' read -ra CID_ARRAY <<< "$CANCELED_JOB_IDS"
            for CID in "${CID_ARRAY[@]}"; do
                CJ_NAME=$(curl -s --header "PRIVATE-TOKEN: ${API_TOKEN}" "${GITLAB_URL}/api/v4/projects/${PROJECT_ID}/jobs/${CID}" | python3 -c "import sys, json; print(json.load(sys.stdin)['name'])")
                echo "   ⛔ ${CJ_NAME}"
            done
            echo ""
        fi

        # Show pipeline link for manual inspection
        PIPELINE_URL="${GITLAB_URL}/${PROJECT_PATH}/-/pipelines/${PIPELINE_ID}"
        echo "=============================================================="
        echo "📎 Pipeline URL: ${PIPELINE_URL}"
        echo "=============================================================="
        echo ""
        exit 1
    fi
else
    echo "❌ No pipelines found!"
    exit 1
fi
