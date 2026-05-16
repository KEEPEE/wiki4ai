# Agent Workflow — wiki4ai Project

This document defines the standard workflow for all agents working on tasks (User Stories) in the **wiki4ai** Taiga project.

---

## 1. Pick a Story

- Query the **wiki4ai** Taiga project for User Stories with status **`New`** or **`Ready`**.
- Select one story to work on.

> **NOTE — Status values are INTEGER IDs, not strings.**  
> Always use integer status IDs when updating story statuses via the API: `44` (New), `45` (Ready), `46` (In progress), `47` (Ready for test), `48` (Done). Do NOT pass string values.

---

## 2. Set Status to `In progress`

- Update the chosen story's status from `New` / `Ready` → **`In progress`** (status ID: **`46`**).
- Use integer status IDs only (see note above).

---

## 3. Implement the Story

- Begin implementing the selected User Story.
- **You may install any tools, runtimes, or dependencies** you need on this Linux machine to complete the work (e.g., Java, Python, Node.js, compilers, libraries, etc.).
- Follow the story requirements and description carefully.

---

## 4. Build / Compile

- After implementation, **build or compile the project**.
- Verify there are no compilation errors.
- If build fails, fix the issues before proceeding.

---

## 5. Write & Run Tests (if applicable)

- Write tests when they add value and make sense for the changes.
- Execute all tests and confirm they pass.
- Fix any failing tests or broken functionality.

---

## 6. Commit & Push to `main`

- If everything passes (build + tests), commit your changes.
- Use a **clear, descriptive English commit message** that accurately reflects what was done.
- Push the commit to the **`main`** branch.

---

## 7. Update Taiga Story

- Open the same User Story in Taiga and update it:
  - **Append** an implementation description (do NOT delete existing content).
  - Describe what was implemented, how it was done, and any relevant details.
- Change the story status to **`Done`** (status ID: **`48`**).
- Use integer status IDs for the status (`48`), not strings.

---

## 8. Send Discord Notification

- After completing all previous steps, send a completion message via Discord using the MCP tool.
- Post the message to the **`wiki4ai`** channel on the **`keepees server`**.
- The message should summarize what was accomplished (story ID, subject, key changes made).

---

## Taiga User Story Status Reference

| Status Name | Integer ID | Description |
|-------------|------------|-------------|
| New | 44 | Default initial state |
| Ready | 45 | Story is ready to be worked on |
| In progress | 46 | Currently being implemented |
| Ready for test | 47 | Implementation complete, awaiting testing |
| Done | 48 | Completed and verified |

---

## Summary Checklist

| Step | Action | Status Value (integer ID) |
|------|--------|---------------------------|
| 1 | Pick a `New` or `Ready` story from Taiga wiki4ai | — |
| 2 | Set status to In progress | `46` |
| 3 | Implement the story (install tools as needed) | — |
| 4 | Build / compile project | — |
| 5 | Write & run tests (if applicable) | — |
| 6 | Commit with English message, push to `main` | — |
| 7 | Append description + set status to Done in Taiga | `48` |
| 8 | Send Discord notification to #wiki4ai on keepees server | — |
