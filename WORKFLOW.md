# Agent Workflow — wiki4ai Project

This document defines the standard workflow for all agents working on tasks in the **wiki4ai** Jira project.

Jira board: https://keepee777.atlassian.net/jira/software/projects/WIKI4AI/boards/35/backlog

---

## 1. Pick a Task

- Query the **WIKI4AI** Jira project for issues that are ready to be worked on (e.g., in backlog or To Do).
- Select one issue to work on.

---

## 2. Set Status to `In Progress`

- Update the chosen issue's status to **`In Progress`**.
- Use the appropriate transition via Jira API or UI.

---

## 3. Implement the Task

- Begin implementing the selected task/issue.
- **You may install any tools, runtimes, or dependencies** you need on this Linux machine to complete the work (e.g., Java, Python, Node.js, compilers, libraries, etc.).
- Follow the issue requirements and description carefully.

---

## 4. Build / Compile

- After implementation, **build or compile the project**.
- Verify there are no compilation errors.
- If build fails, fix the issues before proceeding.

---

## 5. Write & Run Tests — Mandatory

- **The agent MUST always deliver work with ALL tests passing.** This is non-negotiable. No code is considered complete until every test in the suite passes successfully.
- Execute the full test suite after implementation and verify zero failures.
- **If tests fail:** diagnose the root cause and fix it — either repair the broken code or update the tests to reflect correct behaviour.
- **If tests reference non-existent code** (e.g., removed features, deprecated modules that no longer exist in the codebase), those specific tests MAY be removed. This is the ONLY exception where removing tests is permitted.
- **Otherwise, tests MUST NOT be removed.** We aim for the best possible code coverage. Removing valid tests reduces coverage and is not acceptable.
- Write new tests when they add value and make sense for the changes being made.

---

## 6. Commit & Push to `main`

- If everything passes (build + tests), commit your changes.
- Use a **clear, descriptive English commit message** that accurately reflects what was done.
- Push the commit to the **`main`** branch.

---

## 7. Verify Pipeline

- After pushing to `main`, run the dedicated pipeline check script:
  ```bash
  bash scripts/check_pipeline.sh
  ```
- This script polls the GitLab API and waits up to **45 minutes** for the latest pipeline to finish.
- It reports all job statuses and, on failure, fetches and prints the full trace log of each failed job with error lines highlighted.
- **If the pipeline fails**, analyse the printed logs, fix the root cause in code, commit the fix, push again, and re-run this script until the pipeline passes (`exit 0`).
- Only proceed to the next step once the script confirms `🎉 Pipeline completed successfully!`.

---

## 8. Update Jira Issue

- Open the same issue in Jira and update it:
  - **Append** an implementation description (do NOT delete existing content).
  - Describe what was implemented, how it was done, and any relevant details.
- Transition the issue to a completed status (e.g., `Done`, `Resolved`, or equivalent).

---

## 9. Send Discord Notification

- After completing all previous steps, send a completion message via Discord using the MCP tool.
- Post the message to the **`wiki4ai`** channel on the **`keepees server`**.
- The message should summarize what was accomplished (issue key, summary, key changes made).

---

## 10. Image Tags & Rollback (Convention)

Every build must be traceable: **no stack may pin `:latest`**.

- **Tagging:** each `build-*` job in `.gitlab-ci.yml` pushes its image to `git.keepee.duckdns.org/services/wiki4ai/<name>` under **two tags**:
  - `<CI_COMMIT_SHORT_SHA>` — the 8-char commit SHA; immutable, traceable tag (the one you pin), and
  - `latest` — a moving convenience pointer only; never reference it from a stack.
- **Deploy = explicit SHA tag:**
  - The pipeline's `deploy-to-server` job already substitutes the `<IMAGE_TAG>` placeholder in `docker-compose.deploy.yml` with `$CI_COMMIT_SHORT_SHA` before shipping the compose file, so pipeline deploys always run the exact commit that was built.
  - The dev stack on `.219` (`/mnt/data/docker/dockge/stacks/wiki4ai/compose.yaml`) pins explicit SHA tags for the `backend`, `frontend` and `mcp-server` services. To deploy a new build there: verify the tag exists in the registry (`docker manifest inspect git.keepee.duckdns.org/services/wiki4ai/<name>:<SHA>`), update the three `image:` lines to that SHA, then `docker compose up -d` (only the changed services are recreated).
  - The `embedding` sidecar is **not** built by the pipeline yet (WIKI4AI-51) — it keeps its local image until that story lands.
- **Rollback = change the tag back:** edit the pinned SHA in the compose file to a previous commit's SHA (old images remain in the registry indefinitely) and run `docker compose up -d`. No rebuild needed.

---

## Summary Checklist

| Step | Action |
|------|--------|
| 1 | Pick a task from Jira WIKI4AI project backlog | — |
| 2 | Set status to In Progress in Jira | — |
| 3 | Implement the task (install tools as needed) | — |
| 4 | Build / compile project | — |
| 5 | Write & run tests (if applicable) | — |
| 6 | Commit with English message, push to `main` | — |
| 7 | Run `bash scripts/check_pipeline.sh` and wait for success | — |
| 8 | Append description + transition issue to Done in Jira | — |
| 9 | Send Discord notification to #wiki4ai on keepees server | — |
