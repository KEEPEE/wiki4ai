# Agent Workflow — wiki4ai Project

This document defines the standard workflow for all agents working on tasks (User Stories) in the **wiki4ai** Taiga project.

---

## 1. Pick a Story

- Query the **wiki4ai** Taiga project for User Stories with status **`new`** or **`ready`**.
- Select one story to work on.

> **NOTE — Status values are STRINGS, not integers.**  
> Always use string values when updating story statuses via the API: `"new"`, `"ready"`, `"in progress"`, `"done"`. Do NOT pass integer status IDs.

---

## 2. Set Status to `in progress`

- Update the chosen story's status from `new` / `ready` → **`in progress`**.
- Use string values only (see note above).

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
- Change the story status to **`done`**.
- Again, use string values for the status (`"done"`), not integers.

---

## Summary Checklist

| Step | Action | Status Value (string) |
|------|--------|-----------------------|
| 1 | Pick a `new` or `ready` story from Taiga wiki4ai | — |
| 2 | Set status to in progress | `"in progress"` |
| 3 | Implement the story (install tools as needed) | — |
| 4 | Build / compile project | — |
| 5 | Write & run tests (if applicable) | — |
| 6 | Commit with English message, push to `main` | — |
| 7 | Append description + set status to done in Taiga | `"done"` |
