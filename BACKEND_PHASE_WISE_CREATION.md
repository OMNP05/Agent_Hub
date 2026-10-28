# Agent Hub Backend: Phase-Wise Creation Plan

Status: **plan for review, nothing built yet.** Companion to `PHASE_WISE_CREATION.md` (the Android app, done through Phase 10 plus demo mode). The contract the backend must satisfy is `docs/API_CONTRACT.md`. Rules come from `CLAUDE.md` and `architecture.md`; if this file disagrees with them, they win.

---

## 1. The key idea: you do not run a server 24/7

An "agent" is **not a running process**. It is a row in a database (instructions, permission toggles, connection) plus a small stateless program that runs **only when something triggers it**: you send a chat message, you tap Approve, or a schedule fires. Between triggers nothing runs and nothing costs money.

| Trigger | What wakes the backend | What runs |
|---|---|---|
| You chat | the app calls `POST /agents/{id}/chat` | one run, streamed back over SSE, then the instance can sleep |
| You approve | the app calls `POST /approvals/{id}/decision` | runs the stored action, finishes the run |
| A schedule (for example 9 am summary) | Cloud Scheduler calls `POST /internal/tick` | runs due agents, sends a push |
| Push for an approval | FCM (Google's push service) | nothing on your side; the phone shows the notification |

The backend itself is a **Cloud Run** service that **scales to zero** when idle and starts in a second or two on the next request. This is why the design keeps all state in the database (architecture 3.1) and why an approval is "a row waiting", never "a process waiting".

---

## 2. Technology and hosting

| Layer | Choice | Why | Cost |
|---|---|---|---|
| API and agent runtime | **Python + FastAPI**, small custom agent loop, LangChain only for `ChatOpenAI` / `ChatAnthropic` | locked in `CLAUDE.md` | free |
| Hosting | **Google Cloud Run** (scale to zero, container) | scales to zero, supports streaming, same ecosystem as push and scheduler, Google application-default credentials mean fewer key files | free tier, see below |
| Database and sign-in | **Supabase** (Postgres + Auth, row-level security) | locked in `CLAUDE.md` | Free plan |
| Scheduler | **Google Cloud Scheduler**, one job that calls `/internal/tick` | wakes a sleeping service; secured with a Google-signed token | 3 free jobs (verify) |
| Push | **Firebase Cloud Messaging** | locked | free |
| Master encryption key | **Secret Manager** (Cloud Run secret reference) | keeps it out of the database and the image | free tier |
| Build and deploy | **GitHub Actions** to Cloud Build / Artifact Registry to Cloud Run | you already use GitHub | free tier |
| Errors (optional) | Sentry free tier | later | free |

### Facts checked on 2026-10-27 (official pages) and facts still to verify

| Claim | Status |
|---|---|
| Cloud Run free tier: 2 million requests, 180,000 vCPU-seconds, 360,000 GB-seconds per month (request-based billing); 1 GB outbound free only from North America | **verified** (`docs.cloud.google.com/free/docs/free-cloud-features`) |
| Secret Manager: 6 active secret versions and 10,000 access operations per month free | **verified** (same page) |
| Cloud Build 2,500 build-minutes and Artifact Registry 0.5 GB free per month | **verified** (same page) |
| Supabase Free: 500 MB database, 50,000 monthly active users, 5 GB egress, 1 GB storage, 2 active projects; **projects pause after 1 week of inactivity** | **verified** (`supabase.com/pricing`) |
| GitHub App user tokens last 8 hours; refresh tokens last 6 months and **rotate on every use**; refresh is `POST https://github.com/login/oauth/access_token` with `client_id`, `client_secret`, `grant_type=refresh_token`, `refresh_token` | **verified** (GitHub docs) |
| Gmail and Calendar scope URLs (listed in section 6) | **verified** (Google scopes page) |
| Cloud Scheduler: 3 free jobs per billing account | **from memory, verify in Phase B0** |
| Cloud Run request timeout up to 60 minutes and SSE support | **from memory, verify in Phase B9** |
| Which GitHub endpoints accept GitHub App user tokens (`PATCH /user`, follow/unfollow) | **not confirmed by docs, test with a real token in Phase B7** |
| Google Tasks scope URLs | **not verified; Tasks is not in v1 (section 9)** |
| A `*.run.app` URL can host `/.well-known/assetlinks.json` and work as the Android App Link host | **believed to work, verify in Phase B7** |
| Google needs a billing account (card) on the project to use Cloud Run, even inside the free tier | **believed true, confirm in Phase B0** |

### Will the free tier be enough?

Rough estimate, to be replaced by measurements in Phase B12. A run holds an instance only while it works, capped at 2 minutes of active work. Assuming a typical run uses about 20 to 40 seconds of one vCPU (most of that is waiting for the LLM), the 180,000 free vCPU-seconds allow roughly **4,500 to 9,000 runs a month**, far more than one person uses. The practical limits are the Supabase 500 MB database and your own LLM provider bill, not Cloud Run.

### Region (you are in Bengaluru)

Cloud Run's CPU, memory and request allowances are not region-limited, but its free outbound traffic is only for North America. Our responses are small text, so egress costs cents. **Recommendation: Cloud Run in `asia-south1` (Mumbai) and Supabase in its Mumbai region**, so the app, backend and database are close (verify both regions are available in Phase B0). Set a budget alert so a surprise bill is impossible.

### Alternatives considered (not verified)

Render, Fly.io, Koyeb and Railway also offer free or cheap tiers, but free web services there typically sleep or have caps and change often. Cloud Run is the choice unless you object to adding a card to Google Cloud; tell me if you do and I will compare the others properly before Phase B12.

---

## 3. The complete flows

### 3.1 Sign in

```
App --Credential Manager--> Google account picker --> Google ID token (+ our nonce)
App --POST /auth/google {id_token, nonce}--> Backend
Backend --> Supabase Auth (sign in with ID token) --> verifies signature, issuer, audience, expiry, nonce
Backend <-- Supabase session (access JWT 15-60 min, rotating refresh token)
App <-- {access_token, refresh_token}   (stored encrypted on the phone)
Every later call: Authorization: Bearer <JWT>  -> backend verifies the JWT; user_id comes ONLY from the JWT
```

### 3.2 Create and connect an agent (OAuth, run by the backend)

```
App: POST /agents  (template + toggles)            -> row in `agents`, status draft
App: GET /oauth/{github|gmail|calendar}/start?agent_id=...
Backend: checks agent belongs to user; maps toggles -> MINIMUM scopes;
         creates single-use `state` (10 min) + PKCE verifier (server side)
         returns {auth_url}
App: opens auth_url in a Chrome Custom Tab  -> user signs in at GitHub/Google and approves
Provider -> redirects to  https://<backend>/oauth/{connector}/callback?code&state
Backend: validates state (exists, unused, unexpired, matches user+agent), exchanges code (+PKCE +client secret),
         records the scopes ACTUALLY granted, encrypts tokens (AES-GCM envelope), stores in `connections`,
         deletes state, redirects to  https://<backend>/oauth/done?status=ok&agent_id=...
Android opens the app via the verified App Link -> app refreshes the agent -> "Connected"
```

The phone never sees a provider token. One grant per agent.

### 3.3 A chat run (the agent loop)

```
App: POST /agents/{id}/chat {conversation_id, message}      (SSE response)
Backend: verify JWT -> agent belongs to user -> no other active run in this conversation (else 409 agent_busy)
         create `runs` row, save the user message, emit run.started
         load agent + LLM profile; decrypt the LLM key IN MEMORY only
         build context: platform prompt + agent instructions + date/time zone + ONLY the enabled tools + history window
LOOP (max 6 LLM steps, 10 tool calls, 2 min active work):
   call the user's LLM (stream tokens -> message.delta)
   final text?  -> save assistant message, run.completed, stop
   tool call?   -> TOOL GATEWAY:
        agent active? tool in manifest and toggle ON (checked live)? arguments valid? limits ok?
        Read           -> fetch the token, call the provider, trim the result, wrap it as UNTRUSTED DATA, continue
        Write (ask) / Dangerous -> create `approvals` row (exact args + hash), run -> awaiting_approval,
                                    send FCM push + SSE approval.required, END THE REQUEST
        denied/error   -> error result goes back to the LLM
   every call is written to `audit_logs` (secrets redacted)
```

### 3.4 Approval (nothing waits in memory)

```
Phone: push "Work Gmail needs your approval"  -> opens the Activity tab -> card shows the EXACT recipient, subject, body
App: POST /approvals/{id}/decision {approve|reject}
Backend: ONE atomic update  UPDATE approvals SET status=... WHERE id=? AND status='pending'   (0 rows -> 409, nothing runs)
         approve: verify args_hash, run the tool with the STORED arguments only, append result, let the LLM finish
         reject:  tell the LLM "the user rejected this", let it explain
Voice can never approve. Dangerous tools can never be set to "allow".
```

### 3.5 Scheduled run (server asleep)

```
Cloud Scheduler (1 job, every 5 min) --OIDC-signed POST--> /internal/tick
Backend: verifies the Google-signed token (audience + invoker service account), selects schedules that are due,
         starts each run with a SERVICE identity + the stored agent_id (all Gateway rules still apply),
         stores the result, sends an FCM push.  The same tick also touches the database (keeps Supabase from pausing)
         and cleans old run_events.
```

### 3.6 Token refresh, revoke, delete

* Before every provider call: if the access token expires within about 60 seconds, refresh under a per-connection lock (so two parallel calls do not both refresh) and store the new tokens. GitHub rotates the refresh token every time, so the new one **must** be saved atomically.
* `invalid_grant` or revoked: connection `needs_reauth`, run stops, push "Reconnect GitHub".
* Disconnect, delete agent, kill switch, delete account: revoke at the provider where an API exists, delete rows, pause agents (architecture 1.6).

---

## 4. What I need from you (and when)

Everything secret goes into **your own** Google Cloud Secret Manager, Supabase dashboard, or a local `.env` file that is gitignored. **Never paste a secret, key or token into this chat.** I only need the *names* and the non-secret IDs, and I will tell you when a step is blocked.

### 4.1 Accounts and one-time setup

| # | What | Who | Needed by | Notes |
|---|---|---|---|---|
| 1 | **Google Cloud project** with a billing account | you | B0 | Cloud Run needs billing enabled. Create a **budget alert (for example 1 USD)** immediately |
| 2 | Enable APIs: Cloud Run, Cloud Build, Artifact Registry, Secret Manager, Cloud Scheduler, Gmail API, Google Calendar API, Firebase Cloud Messaging | you | B0 | I will give the exact `gcloud services enable` command |
| 3 | **Supabase project** (Free plan), region near you | you | B0 | Use one project; the Free plan allows 2 |
| 4 | **Firebase project** linked to the same Google Cloud project, Android app registered with package `dev.agenthub.app` | you | B10 | Gives `google-services.json` for the app (gitignored) |
| 5 | **Google OAuth consent screen** (External, Testing mode) and test users (your Gmail addresses) | you | B7 | Testing mode: up to 100 named test users, and **refresh tokens expire after 7 days** until you publish and verify |
| 6 | **GitHub App** (not an OAuth App) with expiring user tokens, callback URL, and permissions | you, I give the exact settings | B7 | Details in 4.3 |
| 7 | A **domain you control** (optional for now) | you | before public launch | See "App Link host" below |
| 8 | A **GitHub repository secret** set for deploys | you | B12 | Or Workload Identity Federation, which I will describe so no key file is needed |

### 4.2 Values and where they live

| Name | Secret? | Where it comes from | Stored in |
|---|---|---|---|
| `SUPABASE_URL` | no | Supabase dashboard, API settings | Cloud Run env |
| `SUPABASE_ANON_KEY` | low (public by design) | same | Cloud Run env |
| `SUPABASE_SERVICE_ROLE_KEY` | **yes, server only** | same | Secret Manager |
| `DATABASE_URL` (pooled connection string) | **yes** | Supabase, database settings | Secret Manager |
| JWT verification (public keys URL or secret) | no / yes | Supabase; I will check which signing method your project uses | env |
| `MASTER_ENCRYPTION_KEY` (32 random bytes, base64) and `KEY_VERSION` | **yes, the crown jewel** | **you generate it**, command below | Secret Manager, never in git |
| `GOOGLE_WEB_CLIENT_ID` | no | Google Cloud, Credentials; the same **Web** client ID the Android app uses for sign-in | env and the app's `local.properties` |
| `GOOGLE_CONNECTOR_CLIENT_ID` / `_SECRET` | secret | a **separate Web** OAuth client for Gmail and Calendar consent (architecture 1.2) | Secret Manager |
| `GITHUB_APP_CLIENT_ID` / `_CLIENT_SECRET` | secret | GitHub App settings | Secret Manager |
| `GITHUB_APP_SLUG` | no | GitHub App | env |
| Firebase credentials | none needed if Cloud Run's own service account is granted the messaging role | Firebase / IAM | no key file |
| `PUBLIC_BASE_URL`, `APP_LINK_HOST` | no | your Cloud Run URL or domain | env, and the app's `local.properties` |
| Android release signing **SHA-256 fingerprint** | no | `keytool -list -v -keystore <your keystore>` | goes into `assetlinks.json` |
| Scheduler invoker service account | no | I create the commands | IAM |

Generate the master key yourself, locally, and store it straight into Secret Manager (I will give the exact command):
`python -c "import os,base64;print(base64.b64encode(os.urandom(32)).decode())"`

### 4.3 GitHub App settings (to confirm against GitHub's UI in B7)

* Callback URL: `https://<backend>/oauth/github/callback`. Enable **expiring user authorization tokens** and **Request user authorization (OAuth) during installation**.
* Permissions as needed by the toggles: pull requests (read), followers (read, and write for follow/unfollow), profile (write for editing the profile). The docs I fetched did not say which of these accept App user tokens, so **Phase B7 tests each endpoint with a real token**. If profile edit is not supported, the plan in architecture 1.3 applies: use an OAuth App with the `user` scope for that one agent only.
* Reading PRs only works on repositories where the App is installed. The app already says so on the connect screen.

### 4.4 Google OAuth scopes (verified URLs)

| Toggle | Scope |
|---|---|
| Gmail read | `https://www.googleapis.com/auth/gmail.readonly` |
| Gmail organize | `https://www.googleapis.com/auth/gmail.modify` |
| Gmail drafts | `https://www.googleapis.com/auth/gmail.compose` |
| Gmail send | `https://www.googleapis.com/auth/gmail.send` |
| Calendar read | `https://www.googleapis.com/auth/calendar.readonly` (or `calendar.events.readonly`) |
| Calendar create/edit/invite/delete | `https://www.googleapis.com/auth/calendar.events` |

Scopes are requested **incrementally** and the **Gateway enforces the toggles**, because one Google scope covers several toggles. Gmail's read, modify and compose scopes are *restricted* (architecture 2.5): Google's verification and a security assessment are needed before public users. In Testing mode you and your named test users can use everything now.

### 4.5 App Link host (decision for you)

The Android app needs `https://<host>/.well-known/assetlinks.json` to return the app's package name and signing fingerprint. The backend can serve that file itself, so the **free `*.run.app` URL can work as the host** for building and for you and your test users. A real domain you own is needed for Google's public verification (homepage and privacy policy must be on a domain you control). Decide: start on `*.run.app`, buy a domain later.

---

## 5. Repository layout

```
/backend
  app/
    main.py                    FastAPI app, middleware (request id, redaction)
    config.py                  settings from environment only
    api/                       auth, agents, llm_profiles, oauth, chat (SSE), runs, approvals, devices, logs, account, internal
    runtime/                   agent loop, context window, run lifecycle, limits, SSE events, summaries
    gateway/                   permissions, approvals, token injection, rate limits, audit
    connectors/github|gmail|calendar/   tool definitions + provider calls
    catalog/                   the 3 templates (single source of truth, mirrors the Android catalog)
    llm/                       profile -> client factory, SSRF-safe HTTP
    crypto/                    AES-GCM envelope encryption, key versions
    db/                        models, migrations, RLS policies
    push/                      FCM sender
  tests/                       unit, integration (mock providers), security
  Dockerfile  pyproject.toml  .env.example
```

---

## 6. Phases

Rule for every phase: branch `backend/<name>`, small commits, tests written with the code, run `pytest` and the linter before calling it done, update `docs/API_CONTRACT.md` in the same change, and **ask before committing** (your standing rule). No real email, event or GitHub change in automated tests; providers are mocked.

### B0. Decisions and accounts (you, about half a day)
Build: nothing. Do the accounts in 4.1, answer the questions in section 8, enable APIs, set the budget alert, generate the master key. Only the Supabase project is needed for B1 to B6; the Google Cloud project and billing are needed by B7.
Done when: every row of 4.1 numbered 1 to 3 is ticked and the open decisions are answered.

### B1. Skeleton, tooling, CI
Build: FastAPI app with `/healthz`, settings from env only, structured JSON logging with a redaction filter (`run_id`, `agent_id`, `user_id`; never tokens, keys, email bodies), request-id middleware, uniform error format `{"error":{"code"}}`, `pyproject.toml`, ruff, pytest, `.env.example`, a Dockerfile, GitHub Actions running lint and tests.
Tests: error format, redaction filter hides token-shaped and key-shaped values, config refuses to start without required settings.
Done when: `pytest` and lint pass in CI; the container builds and answers `/healthz` locally.

### B2. Database and row-level security
Build: migrations for `users`, `agents`, `connections`, `oauth_states`, `llm_profiles`, `conversations`, `messages`, `runs`, `run_events`, `approvals`, `audit_logs`, `devices`, `schedules`. **Row-level security on every table** (`user_id = auth.uid()`), indexes from architecture 3.8, a restricted app role plus the service role used only by the runner.
Tests: with two test users, user A can never read or write user B's rows through the API role (one test per table); service role paths filter by `user_id` and `agent_id` in code too.
Done when: an automated RLS check lists every table and fails if any lacks a policy.

### B3. Authentication
Build: `POST /auth/google` (Supabase sign-in with the Google ID token and nonce), `/auth/refresh`, `/auth/logout`, a JWT dependency that verifies signature, expiry and audience and exposes **only** the token's `user_id`, ownership helper (`agent_id` must belong to that user), rate limit per user.
Tests: expired, wrong-audience, tampered and missing tokens rejected; a request body containing a different `user_id` is ignored; agent of another user returns 404.
Done when: the real Android app can sign in against a deployed or local backend (debug build with `DEMO_BACKEND=false`).

### B4. Encryption and secrets
Build: AES-256-GCM envelope encryption (per-record data key wrapped by the master key, `key_version` column), decrypt-in-memory helper, master key from Secret Manager, a rotation command that re-wraps keys.
Tests: round trip, tamper detection, wrong key fails, rotation keeps data readable, ciphertext never equals plaintext, log capture shows no secret after a full encrypt/decrypt flow.
Done when: no code path stores a token or LLM key unencrypted (a test greps the DB fixtures).

### B5. LLM profiles and safe outbound calls
Build: `/llm-profiles` CRUD and test endpoints per the contract (key write-only, only `key_last4` returned), the client factory for OpenAI, Anthropic and OpenAI-compatible URLs, and an **SSRF-safe HTTP client**: https only, resolve DNS and block private, loopback and link-local addresses (including cloud metadata), re-check after redirects, timeouts, size limits.
Tests: every blocked range, DNS rebinding case, redirect into a blocked range, oversized response; an invalid key returns `llm_key_invalid`.
Done when: the Android LLM screens work end to end with a real key.

### B6. Agents API and the template catalog
Build: server-side catalog of exactly **GitHub, Gmail, Calendar** (tools, risk, toggles, defaults), `/agents` CRUD, permission manifest built from toggles, `sanitize` rules (Dangerous never "allow"), pause, kill switch, delete cascades.
Tests: unknown template or connector rejected; a crafted request cannot enable a tool outside the catalog; Dangerous tool set to allow is forced back to ask; kill switch pauses all agents.
Done when: the Android Home, New agent and agent screens work against it.

### B7. Connecting providers (OAuth)
Build: `/oauth/{connector}/start` and `/callback` for GitHub (App user tokens, refresh rotation) and Google (Gmail, Calendar: authorization code + PKCE, `access_type=offline`, `prompt=consent`, incremental scopes), `state` single use and expiring, store the **granted** scopes, `/oauth/done` redirect, `/.well-known/assetlinks.json`, token refresh with a per-connection lock, revoke on disconnect, `needs_reauth` handling.
Tests: forged, reused, expired and mismatched `state` rejected; PKCE verifier required; scopes stored are the granted ones; concurrent refresh yields one refresh; rotated GitHub refresh token is saved; one agent can never read another agent's tokens.
Manual check (needs your accounts): connect each of the three agents from the real app, and test each GitHub endpoint the toggles need with the App user token. Record results in `docs/`.
Done when: all three agents connect from the phone and the App Link returns to the app.

### B8. Tool Gateway and connectors
Build: the Gateway with the exact order from architecture 1.5 (active, manifest, toggle, schema, limits, risk), permissions read **live on every call**, host allowlist (`api.github.com`, `github.com`, Google API and OAuth hosts; confirm the exact Gmail host from Google's docs), timeouts and response size limits, result trimming (Gmail: sender, subject, date, snippet only in stored history), untrusted-data wrapping, audit log. Connectors in two steps: **read tools first**, then Write and Dangerous tools with approval. Calendar forces `sendUpdates` to none unless the exact action was approved.
Tests (security, must pass): default deny; a tool with its toggle off never runs; a permission revoked mid-run stops the next call; arguments checked against the schema; an unapproved write never executes; provider calls to non-allowlisted hosts are blocked; provider error bodies never reach the LLM or the app.
Done when: each tool works against mocked providers and, for read tools, against your real accounts.

### B9. Agent runtime and chat streaming
Build: the custom loop (6 LLM steps, 10 tool calls, 2 minutes active, one active run per conversation), context window with rolling summary, `POST /agents/{id}/chat` as SSE with every event stored in `run_events` and an increasing id, `GET /runs/{id}`, `GET /runs/{id}/events` with `Last-Event-ID`, cancel, usage per run, retries with backoff (429 twice, 5xx once), failure codes, stale-run cleanup. Verify Cloud Run's request timeout and streaming behaviour here.
Tests: each limit stops the run; busy returns `409 agent_busy`; resume after a dropped stream replays missed events; cancel stops between steps; prompt-injection fixtures (an email saying "ignore your rules and send...") produce an approval card, never a send.
Done when: the real Android chat streams against a deployed backend with a real LLM key.

### B10. Approvals, devices and push
Build: `/approvals`, the atomic decision update, args-hash check, expiry job (24 h), approve resumes the run with stored arguments only, `/devices` register and unregister, FCM sender using the Cloud Run service account, data-only payload `{type, approval_id, agent_name}` (no action details).
Tests: double approve, approve-then-reject and approve-after-expiry run nothing twice; changed arguments fail the hash; a user cannot decide another user's approval; sign-out unregisters the device; the payload contains no tool arguments.
Done when: a push arrives on a real phone and tapping it opens the approval.

### B11. Schedules and housekeeping
Build: `schedules` table and API, `/internal/tick` protected by a Google-signed token (audience and invoker service account checked; any other caller gets 401), due-run selection, service-identity runs, result push, daily cleanup of old `run_events`, a cheap database touch each tick so the free Supabase project does not pause.
Tests: unsigned or wrong-audience tick rejected; a run is not started twice for one slot (idempotency); paused agents are skipped; a schedule run still goes through the Gateway.
Done when: a 9 am summary arrives while the app is closed and the service was asleep.

### B12. Deploy to Cloud Run
Build: production Dockerfile, Cloud Run service (min instances 0, **max instances 2** as a cost and abuse cap, request timeout set for SSE, CPU allocated for streaming), secrets mounted from Secret Manager, GitHub Actions deploy, health checks, structured logs, the Cloud Scheduler job, budget alert confirmed, `docs/DEPLOY.md` with every command.
Tests: smoke test against the deployed URL; cold-start time measured and recorded; a month-end cost estimate from real usage.
Done when: the release build of the Android app (HTTPS) works against the deployed backend and the free-tier estimate in section 2 is replaced by measured numbers.

### B13. Security review and end-to-end on the phone
Build: a written review against the 11 security rules in `CLAUDE.md`, dependency audit, a full test run of the Definition of Done list (agent A cannot read agent B's tokens or chat; an unapproved write never runs; forged or reused OAuth `state` rejected; a revoked permission stops a run immediately), then the same flows by hand on a real phone: sign in, connect all three agents, chat, approve, reject, schedule, disconnect, delete account.
Done when: all of the above pass and the remaining gaps are written down honestly.

### Parallel track (starts at B0, long lead time)
* **Google verification:** privacy policy, homepage, demo video, domain verification, and for Gmail's restricted scopes a third-party security assessment. This is the slowest and costliest item and does not block development in Testing mode.
* **GitHub App:** no review process.

---

## 7. Definition of done (every backend phase)

Matches `CLAUDE.md`: tests pass including the security tests for that phase; no secrets in code, logs or the app; every new table has row-level security; every new tool has a schema, a risk level, a scope, a toggle, an approval path if Write or Dangerous, and tests; the API contract and docs are updated; limits and error codes are user-friendly; `.env.example` lists every new setting.

---

## 8. Decisions

**Decided (2026-10-27): hosting is Google Cloud Run** (scale to zero, billing account with a budget alert). The GitHub Student Pack offers (Azure for Students, Heroku credit, a free domain) were considered and not chosen; the free domain from Name.com or Namecheap can still be claimed later for the App Link and Google verification. The "enough for one person" claim in section 2 is an **estimate**; B12 replaces it with measured numbers.

**What actually blocks B1:** only items 6 and 7 below. The host matters from B7 (the OAuth callback needs a public URL, which a temporary tunnel or a quick deploy can provide) and B11/B12 (scheduler and deploy). B1 to B6 build and test locally with no host.

1. **Hosting:** ~~Google Cloud Run or compare others~~ **decided: Cloud Run.**
2. **Region:** Mumbai for Cloud Run and Supabase (recommended for latency), or North America for the free egress?
3. **App Link host:** start on the free `*.run.app` URL and buy a domain later (recommended), or do you already own a domain?
4. **Google Tasks:** you mentioned it, but `CLAUDE.md` locks v1 to exactly GitHub, Gmail and Calendar. Adding Tasks would be a fourth agent, a new template, tools and a new Google scope. I will not add it unless you explicitly approve changing `CLAUDE.md` and `architecture.md`. The plan above is built so it can be added later as another connector.
5. **Open items from `architecture.md` 1.8 and 2.9** still unanswered: Supabase Auth vs another (assumed Supabase), GitHub App with OAuth-App fallback (assumed), whether the backend may keep refresh tokens for scheduled runs (required for schedules; assumed yes).
6. **Where the backend code lives:** a `/backend` folder in this same repository (assumed), or a separate repository.
7. **Permission to proceed:** per `CLAUDE.md` I wait for your approval of this plan before writing backend code.

---

## 9. Out of scope for the backend v1

Custom or user-written agents, any connector beyond GitHub, Gmail and Calendar, Google Tasks, Todoist, Telegram, Notion, MCP servers, attachments, bulk send, a marketplace. These need explicit approval to change `CLAUDE.md` first.

---

## 10. Known risks

* **Cold starts:** the first request after idle waits a second or two; the app already shows loading states.
* **Supabase pause:** a free project pauses after 1 week without activity; the scheduler tick prevents it, but verify after deploy.
* **Google Testing mode:** refresh tokens expire after 7 days until the app is verified, so scheduled Gmail or Calendar runs will need a reconnect weekly during development.
* **Gmail verification** is slow and may cost money; plan for GitHub and Calendar first if it is not done at launch.
* **Prompt injection** is only reduced by prompts; the Gateway, approvals and limits are the real protection.
* **Facts marked "verify"** in section 2 may be wrong; each is checked in the phase named beside it before anything depends on it.

---

## 11. Progress tracker

| Phase | Branch | Commit | Checks | Status |
|---|---|---|---|---|
| B0 | none | n/a | Supabase project (Tokyo, ap-northeast-1) created; session-pooler connection to PostgreSQL 17.11 verified with `select`; secret key is the new `sb_secret_` format; `backend/.env` gitignored | Supabase part done. Google Cloud, Firebase, Google OAuth clients and GitHub App still to do (needed by B7 and B10) |
| B1 | `backend/skeleton` | 31acafe | `ruff check`, `ruff format --check`, 47 pytest tests pass; real uvicorn run: `/healthz` 200, error format, request id, no query secrets in logs. Docker image NOT built (Docker is not installed on this machine); CI workflow not yet run on GitHub | Done, pushed |
| B2 | `backend/database` | 11c36fc | `ruff check`, `ruff format --check`; 193 pytest tests pass against the real Supabase Postgres, every one inside a rolled-back transaction; afterwards the real database has 0 tables and 0 test users. Without a database (CI) 50 pass and 143 skip. The migration has NOT been applied to your real Supabase project | Done, pushed. Migration not yet applied to the real project |
| B3 | `backend/auth` | uncommitted | `ruff check`, `ruff format --check`; 266 pytest tests pass (73 new, no database needed). Verified against the LIVE project: its JWKS has one ES256 key, a forged token using the real kid is rejected, and the running server returns the right 401/422/503 for no token, junk token, bad body, a fake Google token and a fake refresh token. NOT tested: a real Google sign-in end to end (needs the Google client ID and the Google provider enabled in Supabase) | Done, awaiting commit |
| B4 to B13 | | | | Not started |
