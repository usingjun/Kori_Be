# Agent Rules

## Project
Existing backend project. Read existing code before editing.

## Rules
- Do not rewrite architecture unless explicitly requested.
- Prefer minimal, reviewable changes.
- Preserve existing naming/style conventions.
- Add or update tests when changing business logic.
- Do not modify secrets, env files, or deployment configs unless requested.
- Explain changed files and validation result.
- Read docs/agent/project-context.md before editing code. 
- Use the actual tech stack defined there. 
- Do not assume missing technologies.
- Before editing, summarize the intended change plan and wait only if the user explicitly asked for approval.
- Follow the project-specific rules in docs/agent/project-context.md.
- Before modifying high-risk areas, explain the affected flow first.
- High-risk areas include security/JWT/OAuth, WebSocket/chat, payment/IAP, image storage, search/PGroonga, Flyway migrations, external API integrations, and admin side-effect endpoints.
- Never revert or overwrite existing user changes in the working tree.

## Documentation

Before starting work, review the following documents if they exist:

* docs/agent/progress.md
* docs/agent/decision-log.md
* docs/agent/handoff.md

If the current task affects project status, architectural decisions, or future handoff, update the relevant documents before considering the task complete.

Documentation updates are part of the Definition of Done.

## Context Management

When context usage becomes high or a task is expected to continue in another session:

* Update docs/agent/progress.md with the latest task status.
* Update docs/agent/decision-log.md with important design decisions and trade-offs.
* Update docs/agent/handoff.md so another session can continue the work without relying on previous conversation history.

Prioritize recording why decisions were made, not only what was implemented.


## Validation
Run:

./scripts/agent-check.sh