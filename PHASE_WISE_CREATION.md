# Agent Hub: Phase-Wise Creation Plan

Authoritative roadmap and tracker for the **Android app**. Sources: `CLAUDE.md` (locked decisions), `architecture.md` sections 1 to 3, `Agent Hub Project Blueprint v0.1 (2).md`. If this file and `architecture.md` disagree, `architecture.md` wins; stop and ask.

## 1. Scope

The phone is the remote control for cloud-run agents (GitHub, Gmail, Google Calendar): sign-in, agent management, permission toggles, streaming chat, voice, approvals, activity log, LLM profiles, settings.

**Not built here:** FastAPI backend, Supabase schema, FCM sender, GitHub/Google app registration. The app talks to the backend only through `AgentHubApi` (`docs/API_CONTRACT.md`).

**Never in the app:** provider tokens, LLM keys (write-only; only last 4 characters shown), client secrets, a "Custom" agent, any connector beyond the three.

## 2. Environment status (updated 2026-10-26)

| Item | Status |
|---|---|
| Git repo | Not initialised. `git init` + `origin` = `https://github.com/OMNP05/Agent_Hub.git` was **denied by the tool permission classifier** in Bash and PowerShell despite user approval. User must run it or add a permission rule |
| Remote | Exists, empty, no branches (checked via GitHub MCP as OMNP05) |
| `gh` | Not logged in; push needs git credentials |
| JDK 17 | Installed via winget (Microsoft OpenJDK 17.0.20); new shells may be needed to see PATH |
| Android Studio / SDK | Install started via winget; SDK platforms/build-tools still need downloading (`sdkmanager`) |
| Gradle | No standalone install; wrapper will be generated from Android Studio's bundled Gradle or downloaded |

## 3. Assumptions (user approved 2026-10-26)

1. Several agents may be created from the same template (e.g. two Gmail accounts).
2. `gmail.send_email` ships in v1 as Dangerous (always asks).
3. Architecture section 9 is unwritten; `docs/ARCHITECTURE.md` is a proposal.
4. `API_BASE_URL` and `GOOGLE_WEB_CLIENT_ID` come from `local.properties` (never committed). Debug default `http://10.0.2.2:8000/`; release requires HTTPS.
5. Session tokens stored in DataStore encrypted with an Android Keystore AES-GCM key.
6. Single module `:app`, feature packages. Package `dev.agenthub.app` (working name).
7. Min SDK 26; compile/target SDK = latest stable installed (verify).
8. API JSON shapes are our proposal until section 7 of `architecture.md` exists.
9. Voice via `SpeechRecognizer`/`TextToSpeech`; can start requests, never approve.

## 4. Git workflow

One branch per phase (name in each phase), conventional commits, small commits within a phase. Phase branches stack on the previous one while unmerged. No merge to `main`, no PR, no force-push without the user's go-ahead. Never commit `local.properties`, keystores, real `google-services.json`, `.env`, build output.

## 5. Phases (what gets built, in detail)

Every phase ends with: tests written, `./gradlew` checks run, diff reviewed, tracker updated, commit, push if auth allows.

### Phase 0: Assessment and docs (`docs/project-roadmap`)
Build: this file, `docs/REQUIREMENTS.md`, `ARCHITECTURE.md`, `API_CONTRACT.md`, `DEVELOPMENT.md`, `.gitignore`, `CLAUDE.md` command section.
Done when: docs agree with locked decisions. **Status: written, uncommitted (no repo).**

### Phase 1: Toolchain and foundation (`feat/project-foundation`)
Build:
- Gradle Kotlin DSL project, `libs.versions.toml`, wrapper, `:app` module, `.gitignore`.
- Plugins/deps: Compose BOM, Material 3, Hilt + KSP, Retrofit + OkHttp + kotlinx.serialization, Navigation Compose, DataStore, Room, Coil (not needed yet, skip unless used), JUnit, Turbine, MockWebServer, coroutines-test.
- `AgentHubApplication` (`@HiltAndroidApp`), `MainActivity`, `BuildConfig` fields from `local.properties`, manifest (`allowBackup=false`, cleartext only in debug network config).
- Theme: own accent colour (deep violet/teal, not Spotify green), dark/light/system, typography, shapes; adaptive launcher icon (own logo).
- Core types: sealed `DataError`, `UiText`, `AppResult`, dispatcher provider.
Verify: `assembleDebug`, `testDebugUnitTest` (a theme/config smoke test).

### Phase 2: Navigation shell and shared UI (`feat/compose-navigation`)
Build:
- Four-tab bottom bar (Home, Activity, Connectors, Settings) with separate back stacks and saved state.
- Persistent mini-bar above the bar (active agent + mic), expandable to chat.
- Animated enter/exit transitions; shared-element agent card to chat.
- Components: `SkeletonLoader`, `EmptyState`, `ErrorState(retry)`, `RiskBadge`, `AgentCard`, press animations.
- Route definitions (typed), deep-link for `https://<domain>/oauth/done`.
Verify: nav unit tests; Compose UI tests (if emulator available).

### Phase 3: Auth and session (`feat/auth-session`)
Build:
- Sign-in screen; Credential Manager Google ID option with random nonce.
- `AuthApi` (`/auth/google`, `/auth/refresh`, `/auth/logout`), `SessionStore` (Keystore AES-GCM + DataStore), `AuthInterceptor`, `TokenAuthenticator` (refresh once on 401, rotate refresh token, sign out on reuse error).
- Root `SessionViewModel` deciding Sign-in vs Main; sign-out clears session and unregisters device.
Verify: MockWebServer tests for refresh/401 loop; session store round-trip; ViewModel state tests.

### Phase 4: Data layer (`feat/data-integration`)
Build:
- DTOs + mappers for agents, templates, connections, conversations, messages, runs, approvals, logs, LLM profiles.
- `AgentHubApi` Retrofit interface; error body parser to `DataError` codes (`llm_key_invalid`, `connection_needs_reauth`, `approval_expired`, `agent_busy`, network, unauthorized, unknown).
- Room: agents, conversations, messages (cache, JSONB blocks as JSON text); repositories with cache-then-network flows.
- Hilt modules.
Verify: repository tests with MockWebServer + in-memory Room; no raw body leaks.

### Phase 5: Agents (`feat/agents`)
Build:
- Static template catalog (exactly 3: GitHub, Gmail, Calendar) with toggles, tools, risk, defaults from architecture 2.2 to 2.4.
- Home: Your agents, Templates, Recently used rows; empty state; floating New Agent.
- New Agent flow: pick template, toggles screen (Read on/off, Write Ask/Allow, Dangerous Ask only with no Allow control), Connect (backend `/oauth/{connector}/start` -> Custom Tab), return via App Link shows status + granted scopes, incremental consent prompt when enabling a new toggle.
- Agent detail/edit: name, instructions, LLM profile, schedule (off by default), time zone; Deploy, Pause, Delete (confirm), kill switch.
- Connectors tab: per-agent connection status, reconnect for `needs_reauth`, disconnect.
Verify: catalog test (3 templates; no Allow for Dangerous), ViewModel tests, UI tests.

### Phase 6: Chat and streaming (`feat/chat`)
Build:
- Chat screen: message list, composer, streaming deltas, tool cards ("Fetched 3 open PRs"), Stop (`/runs/{id}/cancel`), "agent is busy" state, error banners.
- SSE client over OkHttp with `Last-Event-ID` reconnect; fallback `GET /runs/{id}`.
- Reducer turning events (`run.started`, `message.delta`, `tool.*`, `approval.required`, `run.completed`, `run.error`) into UI state.
- One ongoing conversation per agent, New chat, conversation history list, delete conversation / clear history.
Verify: SSE parser + reducer tests for every event; rotation-safe state.

### Phase 7: Approvals, activity, push (`feat/approvals-activity`)
Build:
- Approval card with exact tool and full arguments (recipients, subject, body, bio text, attendees); Approve/Reject once-only (button disabled while in flight); expired state.
- Activity tab: Pending approvals + audit log per agent with filters.
- FCM service, notification channel, tap-to-open approval, token registration (`POST /devices`), unregister on sign-out. `google-services.json` supplied locally, not committed.
Verify: double-tap sends one request; approval UI has no voice path; notification intent tests.

### Phase 8: Voice (`feat/voice`)
Build:
- Mic permission flow (rationale, denied, permanently denied).
- `SpeechRecognizer` (en-IN, hi-IN) with transcript editable before sending; mini-bar mic starts a request for the active agent.
- `TextToSpeech` speaks a short summary; stop control.
Verify: a test asserting the voice layer has no dependency on the approvals API; state tests (idle/listening/error/unavailable).

### Phase 9: LLM profiles and settings (`feat/settings-llm`)
Build:
- Profiles list/create/edit: provider, base URL (client-side HTTPS check), key (password field, write-only, shows last 4), model free text, temperature, max tokens, default; Test connection; notice that keys are stored encrypted on our backend.
- Settings: theme (Dark/Light/System, DataStore), biometric app lock (AndroidX Biometric), notification settings, export data, delete account (confirm), sign out, kill switch.
Verify: validation tests; key never rendered after save; theme persistence test.

### Phase 10: Hardening and release (`test/integration-and-release`)
Build:
- Lint clean-up, accessibility pass (content descriptions, 48dp), no-secrets scan, R8/ProGuard rules, release build config, fake-backend end-to-end tests (MockWebServer) for sign-in -> create agent -> chat -> approval.
- Docs sync, limitation list.
Verify: `lintDebug testDebugUnitTest assembleDebug assembleRelease`; instrumented tests only if an emulator exists.

## 6. What cannot be verified here
Real backend, Google sign-in, GitHub/Google OAuth, FCM delivery, speech recognition quality, real device behaviour. These will be reported as unverified.

## 7. Risks and open questions
- SDK download size and install time; licences must be accepted.
- Backend contract unconfirmed; drift risk until agreed.
- Domain for App Links is undecided (architecture 1.8 #3); deep-link host is a build config placeholder.
- Git init and push depend on the user (see section 2).

## 8. Progress tracker

| Phase | Branch | Commit | Build / tests | Status |
|---|---|---|---|---|
| 0 | `docs/project-roadmap` | none | n/a | Docs written |
| 1 | `feat/project-foundation` | | | Toolchain installing |
| 2 to 10 | | | | Not started |
