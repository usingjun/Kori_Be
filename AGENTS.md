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

## Validation
Run:

./scripts/agent-check.sh