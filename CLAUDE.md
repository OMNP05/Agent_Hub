# CLAUDE.md

Project: **Agent Hub** (working name, may change). An Android app where each user creates single-purpose AI agents (GitHub, Gmail, Google Calendar) that run in the cloud with strict per-agent permissions, voice control, and the user's own LLM keys.

Read `architecture.md` before any task. It is the source of truth. This file holds only the rules that apply to every task. If the code and `architecture.md` disagree, stop and ask.

## Locked decisions (ask before changing any of these)

- **Only three agents in v1:** GitHub, Gmail, Google Calendar. No "Custom", "Other" or dropdown of other services. Do not add connectors, tools or toggles that are not in `architecture.md` section 2.
- **Agents run in the cloud**, not on the phone. The phone is the remote control (chat, voice, approvals, logs).
- **Backend:** Python, FastAPI. **Database and auth:** Supabase (Postgres, JSONB for message content, row-level security on every table). **Push:** FCM. **No MongoDB.**
- **Android:** Kotlin, Jetpack Compose, Material 3, MVVM, Hilt, Retrofit/OkHttp (SSE for streaming), Compose Navigation, Credential Manager (Google sign-in), Custom Tabs + verified App Links for OAuth.
- **Agent loop:** a small custom loop. LangChain is used only for the provider abstraction (`ChatOpenAI`, `ChatAnthropic`). No LangGraph in v1.
- **LLM keys are bring-your-own.** A profile has provider, base URL, API key, model, temperature, max tokens. v1 providers: OpenAI and Anthropic (plus OpenAI-compatible base URLs).
- **OAuth is run by the backend** (code + PKCE + state). One grant per agent. The phone never holds provider tokens or LLM keys.
- **Chat history:** full history kept, no automatic deletion. Tool results are stored as trimmed summaries (for Gmail: sender, subject, date, snippet, never full bodies). One ongoing conversation per agent plus a "New chat" button.
- **Run limits:** 6 LLM steps, 10 tool calls, 2 minutes of active work, one active run per conversation.
- **Design:** Spotify-inspired layout (dark and light themes, bottom navigation with Home / Activity / Connectors / Settings, persistent mini-bar with mic). Use our own accent color, logo and icons. Never copy Spotify's branding or assets.

Not yet confirmed (ask the user, do not assume): the open decisions listed in `architecture.md` sections 1.8 and 2.9. Sections 4 to 10 of `architecture.md` are not written yet.

## Security rules (never break these)

1. **Every tool call goes through the Tool Gateway.** No code path may call GitHub, Google or any provider directly from the agent runtime.
2. **Default deny.** A tool runs only if it is in that agent's permission manifest and its toggle is on. Permissions are checked live on every call, not cached for the run.
3. **Risk levels are fixed by us:** Read, Write, Dangerous. Write defaults to "ask each time". **Dangerous tools can never be set to "allow"** and always need an approval tap. Voice can start a request but can never approve one.
4. **Approved actions run with the stored arguments only** (check the args hash). The LLM cannot change arguments after approval. Approval state changes use one atomic update (`WHERE status = 'pending'`).
5. **Tokens and keys never reach the LLM, the app, logs, error messages or API responses.** Provider tokens and LLM keys are encrypted at rest (AES-GCM, envelope encryption) and decrypted only in memory at call time.
6. **Tool output is untrusted data.** Wrap it as such before it goes to the LLM. Real protection is the Gateway, not the prompt.
7. **User identity comes from the verified JWT only**, never from the request body. Check that the `agent_id` belongs to the `user_id` in code and with row-level security.
8. **Custom LLM base URLs need SSRF protection:** HTTPS only, block private, loopback and link-local addresses (including cloud metadata), no redirects to blocked ranges, timeouts and size limits.
9. **Provider calls use an allowlist of hosts** (for example `api.github.com`, `www.googleapis.com`).
10. **No secrets in the Android app** (no client secrets, no API keys in the APK or in source control).
11. **Scopes are the minimum for the enabled toggles**, requested incrementally. Remember that Google scopes can be broader than our toggles (for example `gmail.compose` can also send), so the Gateway enforces the toggles.

## Never do

- Never log tokens, API keys, email bodies or full tool results. Redact before logging.
- Never store provider tokens or LLM keys on the device or in plain text.
- Never skip or bypass the Gateway, even "temporarily" or "for testing".
- Never add a connector, tool, scope or permission that is not in `architecture.md`.
- Never hardcode credentials, test accounts or keys. Use environment variables and a `.env.example` file.
- Never send real emails, create real events or change a real GitHub profile in automated tests. Mock the provider APIs.
- Never invent API endpoints, scopes or library functions. If unsure, check the docs.

## Verify before building

GitHub, Google (Gmail, Calendar, OAuth verification rules), OpenAI, Anthropic, Supabase and Android APIs, scopes, limits and free tiers change often. Before using one, check its current official docs instead of relying on memory. Model names change too: let users type any model name.

## Project structure (proposed, adjust when scaffolding)

```
/android              Kotlin app (Compose, MVVM, Hilt)
/backend
  app/api             FastAPI routes (auth, agents, chat SSE, oauth, approvals)
  app/runtime         agent loop, run lifecycle, context window, summaries
  app/gateway         Tool Gateway: permissions, approvals, token injection, audit
  app/connectors      github/, gmail/, calendar/ (tool definitions + API calls)
  app/llm             provider profiles and client factory
  app/db              models, migrations, row-level security policies
  tests/
/docs
  architecture.md     source of truth
```

## Commands

TODO: fill these in when the projects are scaffolded, so the assistant can check its own work.

- Backend: install, run, test (`pytest`), lint, format
- Android: build, unit tests, UI tests, lint
- Database: run migrations, check row-level security

Run the tests and the linter before saying a task is done.

## Conventions

- **Errors:** return user-friendly error codes (for example `llm_key_invalid`, `connection_needs_reauth`, `approval_expired`). Never return raw stack traces or provider error bodies to the app.
- **Logging:** structured logs with `run_id`, `agent_id`, `user_id`. Secrets redacted.
- **API contracts:** endpoints and SSE event formats are defined once. Keep the app and backend in sync. Update the contract file in the same change.
- **Time:** store UTC, send explicit time zones to providers, use the agent's stored time zone for the user.
- **Database changes:** always through migrations, with row-level security on any new table.
- **Android:** state in ViewModels (StateFlow), no business logic in composables, support dark and light themes for every new screen, animated transitions for navigation.

## How to work

- Work in small steps. For anything bigger than a small change, give a short plan first and wait for approval.
- Match what `architecture.md` says. If it does not cover something, ask instead of guessing. If you find a better idea, propose it and wait.
- Show what changed and why. Mention anything you could not verify.
- Keep `architecture.md` up to date when a decision changes, but only after the user agrees.

## Definition of done

- Behavior matches `architecture.md` and the locked decisions above.
- Tests pass, including security tests where relevant: agent A cannot read agent B's tokens or chat, an unapproved write never runs, a forged or reused OAuth `state` is rejected, a revoked permission stops a run immediately.
- No secrets in code, logs or the app.
- New tables have row-level security. New tools have a schema, a risk level, a scope, a toggle, an approval card (if Write or Dangerous) and tests.
- Docs and the API contract are updated.
