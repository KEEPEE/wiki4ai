#!/bin/bash
# GitLab CI Pipeline Monitor for wiki4ai
# Usage: ./scripts/monitor-pipeline.sh [commit_sha]
# Environment variables required:
#   - GITLAB_TOKEN: Personal access token with api scope
#   - GITLAB_URL: GitLab instance URL (default: https://git.keepee.duckdns.org)

set -e

GITLAB_URL="${GITLAB_URL:-https://git.keepee.duckdns.org}"
PROJECT_ID="services/wiki4ai"
COMMIT_SHA="${1:-$(git rev-parse HEAD)}"
MAX_WAIT_MINUTES=30
POLL_INTERVAL_SECONDS=15

echo "=== GitLab CI Pipeline Monitor ==="
echo "Project: ${PROJECT_ID}"
echo "Commit:  ${COMMIT_SHA}"
echo "URL:     ${GITLAB_URL}"
echo ""

# Check for required token
if [ -z "$GITLAB_TOKEN" ]; then
    echo "ERROR: GITLAB_TOKEN environment variable is not set."
    echo "Please export a GitLab personal access token with 'api' scope:"
    echo "  export GITLAB_TOKEN=glpat-xxxxxxxxxxxx"
    exit 1
fi

# Get pipeline ID for this commit
echo "[INFO] Fetching pipeline for commit ${COMMIT_SHA}..."
PIPELINE_RESPONSE=$(curl -s --fail-with-body \
    -H "PRIVATE-TOKEN: $GITLAB_TOKEN" \
    "${GITLAB_URL}/api/v4/projects/${PROJECT_ID}/pipelines?sha=${COMMIT_SHA}")

if [ $? -ne 0 ]; then
    echo "[ERROR] Failed to fetch pipeline information."
    echo "$PIPELINE_RESPONSE"
    exit 1
fi

PIPELINE_ID=$(echo "$PIPELINE_RESPONSE" | jq -r '.[0].id // empty')

if [ -z "$PIPELINE_ID" ]; then
    echo "[WARN] No pipeline found for commit ${COMMIT_SHA}. Pipeline may not have started yet."
    echo "       Waiting up to 2 minutes for pipeline to appear..."
    
    START_TIME=$(date +%s)
    while true; do
        CURRENT_TIME=$(date +%s)
        ELAPSED=$((CURRENT_TIME - START_TIME))
        
        if [ $ELAPSED -gt 120 ]; then
            echo "[ERROR] Pipeline did not start within 2 minutes."
            exit 1
        fi
        
        sleep 5
        PIPELINE_RESPONSE=$(curl -s --fail-with-body \
            -H "PRIVATE-TOKEN: $GITLAB_TOKEN" \
            "${GITLAB_URL}/api/v4/projects/${PROJECT_ID}/pipelines?sha=${COMMIT_SHA}")
        
        PIPELINE_ID=$(echo "$PIPELINE_RESPONSE" | jq -r '.[0].id // empty')
        if [ -n "$PIPELINE_ID" ]; then
            echo "[INFO] Pipeline found: #${PIPELINE_ID}"
            break
        fi
        
        echo -n "."
    done
    echo ""
fi

echo "[INFO] Monitoring pipeline #${PIPELINE_ID}"
echo "[INFO] Status URL: ${GITLAB_URL}/${PROJECT_ID}/pipelines/${PIPELINE_ID}"
echo ""

# Monitor pipeline status
START_TIME=$(date +%s)
MAX_WAIT_SECONDS=$((MAX_WAIT_MINUTES * 60))

while true; do
    CURRENT_TIME=$(date +%s)
    ELAPSED=$((CURRENT_TIME - START_TIME))
    
    if [ $ELAPSED -gt $MAX_WAIT_SECONDS ]; then
        echo "[ERROR] Pipeline monitoring timed out after ${MAX_WAIT_MINUTES} minutes."
        exit 1
    fi
    
    # Get pipeline status
    PIPELINE_STATUS=$(curl -s \
        -H "PRIVATE-TOKEN: $GITLAB_TOKEN" \
        "${GITLAB_URL}/api/v4/projects/${PROJECT_ID}/pipelines/${PIPELINE_ID}" | jq -r '.status')
    
    ELAPSED_MINUTES=$((ELAPSED / 60))
    echo "[${ELAPSED_MINUTES}m] Pipeline status: ${PIPELINE_STATUS}"
    
    case "$PIPELINE_STATUS" in
        success)
            echo ""
            echo "=== PIPELINE SUCCESSFUL ==="
            exit 0
            ;;
        failed|canceled)
            echo ""
            echo "=== PIPELINE FAILED ==="
            
            # Get failed jobs details
            echo "[INFO] Fetching failed job details..."
            FAILED_JOBS=$(curl -s \
                -H "PRIVATE-TOKEN: $GITLAB_TOKEN" \
                "${GITLAB_URL}/api/v4/projects/${PROJECT_ID}/pipelines/${PIPELINE_ID}/jobs?status=failed")
            
            echo ""
            echo "--- Failed Jobs ---"
            echo "$FAILED_JOBS" | jq -r '.[] | "Job: \(.name) (ID: \(.id))"'
            
            # Get trace for each failed job
            echo ""
            echo "--- Job Traces ---"
            echo "$FAILED_JOBS" | jq -r '.[].id' | while read JOB_ID; do
                if [ -n "$JOB_ID" ]; then
                    JOB_NAME=$(echo "$FAILED_JOBS" | jq -r ".[] | select(.id == $JOB_ID) | .name")
                    echo ""
                    echo "=== Trace for: ${JOB_NAME} (ID: ${JOB_ID}) ==="
                    curl -s \
                        -H "PRIVATE-TOKEN: $GITLAB_TOKEN" \
                        "${GITLAB_URL}/api/v4/projects/${PROJECT_ID}/jobs/${JOB_ID}/trace" | tail -100
                fi
            done
            
            exit 1
            ;;
        running|pending)
            sleep $POLL_INTERVAL_SECONDS
            ;;
        *)
            echo "[WARN] Unknown status: ${PIPELINE_STATUS}"
            sleep $POLL_INTERVAL_SECONDS
            ;;
    esac
done
