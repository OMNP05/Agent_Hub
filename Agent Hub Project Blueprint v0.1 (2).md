# Agent Hub: Project Blueprint v0.1

An Android app where each user creates and manages their own single-purpose AI agents (GitHub, To-do, and more), with strict per-agent permissions, voice control, and bring-your-own LLM keys. Draft for review: tell me what to change.

## 1. Core idea in one paragraph

The user opens the app and sees a list of their agents. Each agent has one job (for example, a GitHub agent that reads PRs and edits the profile), its own permissions, and its own connected accounts. Agents run in the cloud and call each service's API directly. The phone is the remote control: chat, voice, approvals, and logs. The user picks the LLM (ChatGPT or Claude) by entering a base URL, API key and model name.

## 2. Version 1 scope

**In v1**

- Android app (Kotlin, Jetpack Compose)
- Create agents from templates, no code, in about one minute
- Connectors: GitHub, Google Tasks, Todolist, Telegram (more later)
- Per-agent permission toggles and approval for risky actions
- Chat plus tap-to-talk voice
- Bring-your-own LLM: OpenAI (ChatGPT) and Anthropic (Claude), plus any OpenAI/Anthropic-compatible base URL(Specially for the IBM is providing them through thrier platform base URL and API Key so for those kind of user whose company gives custom base URL and api key by tighing up with the Cluade)
- Activity log and kill switch
- Push notifications for approvals and scheduled results

**Not in v1**

- Custom-code agents (planned for v2, see section 5)
- Food delivery (Swiggy, Zomato): no public API
- On-device control of other apps
- Marketplace, wake word, iOS

## 3. Feature list

### 3.1 Agents

- Agent card: name, icon, status, last activity
- Create from template or Custom (no code)
- Agent configuration: name, system instructions (plain words), allowed tools, permissions, LLM profile, triggers
- Triggers: on demand (chat or voice),

  |  |  |  |
  | --- | --- | --- |
  |  |  |  |
  |  |  |  |

  &#32;scheduled (for example every morning), event-based (webhook, later)
- Per-agent memory (short conversation history and optional notes), isolated from other agents
- Pause, delete, and revoke access in one tap

### 3.2 Chat and voice

- Chat screen per agent with streaming replies
- Tool call cards that show what the agent did ("Fetched 3 open PRs")
- Tap-to-talk mic button, transcript shown before sending
- Spoken replies kept short, details shown on screen
- Voice never approves a risky action by itself: approval needs a tap

### 3.3 Permissions and approvals

- Permission screen with toggles per capability (read PRs, read followers, edit profile)
- Risk levels: Read (auto-allowed), Write (ask once or always, user's choice), Dangerous (always ask: delete, spend money, send messages)
- Approval via push notification or in-app card with Approve or Reject
- Pending Approvals screen

### 3.4 LLM settings

- Add one or more LLM profiles, each with provider, base URL, API key, model, temperature, max tokens
- Choose a profile per agent, plus a default
- Test connection button
- Spend limits: max tokens per run and max runs per day per agent

### 3.5 Account and app

- Sign in with Google
- Optional biometric lock for the app
- Notification settings
- Data export and delete account

### 3.6 App design and theme

The app follows a Spotify-inspired design, with dark and light modes and smooth, familiar navigation.

- Style: dark-first look with high-contrast text, rounded cards, large touch targets, and bold section headings, like Spotify
- Themes: dark and light modes, with a switch in Settings and a "Follow system" option
- Own identity: use similar layout and feel, but pick your own accent color, logo and icons (do not copy Spotify's branding, green, or assets)
- Bottom navigation bar with 4 tabs: **Home** (agent cards), **Activity** (logs and pending approvals), **Connectors** (accounts and permissions), **Settings** (LLM profiles, theme, account)
- Persistent mini-bar above the navigation bar, like Spotify's mini player: shows the active agent and a mic button, and expands to the full chat or voice screen with a swipe up
- Home screen: horizontal scrolling rows of agent cards ("Your agents", "Templates", "Recently used"), plus a floating **+ New Agent** button
- Smooth motion: animated screen transitions, shared-element transition from an agent card to its chat screen, skeleton loaders instead of spinners, and subtle press animations
- Same navigation everywhere: back stack and tab state are preserved when switching tabs, and system back works as expected
- Build with Jetpack Compose, Material 3 theming (color scheme, typography and shape tokens for both themes), and Compose Navigation with animated transitions

## 4. End-user flows

### 4.1 First launch

1. Sign in with Google
2. Add an LLM profile (pick OpenAI or Anthropic, paste key, choose model, Test)
3. Home screen shows the empty state with **+ New Agent**

### 4.2 Create an agent (about 1 minute)

1. Tap **+ New Agent**, pick a template (GitHub Assistant, To-do, Telegram Notifier) or Custom
2. Tap **Connect GitHub** (normal OAuth login in a browser tab, then back to the app)
3. Permissions screen: toggle what the agent may do
4. Optional: describe behavior in plain words
5. Pick an LLM profile
6. Test in a chat preview, then tap **Deploy**

### 4.3 Use an agent

1. Open the agent, tap the mic or type: "Show my open PRs"
2. App sends the message to the backend, which runs the agent
   1. Reply streams back, and the app reads a short summary aloud

### 4.4 Risky action

1. User says: "Change my GitHub bio to Android dev"
2. Agent prepares the action, the gateway sees it is a Write and pauses
3. Phone gets a push or in-app card: "GitHub agent wants to update your bio to X"
4. User taps Approve, the gateway executes, and the result is logged

### 4.5 Scheduled run

1. Scheduler triggers the agent (for example 9 AM daily)
2. Agent runs, summarizes, and sends a push with the result

## 5. Custom code vs ready-made agents

**Decision for v1: ready-made and no-code agents only.** An agent is a configuration (instructions, tools, permissions, triggers), not code. This keeps security simple because you control the runtime.

**v2 plan for custom code:** developers build their own tool server using MCP (Model Context Protocol) on their PC, deploy it anywhere (their own free cloud account), and register its URL in the app. Your backend calls it like any other connector, through the Tool Gateway with permissions, logging and approvals. You never run their code on your infrastructure.

This answers the question "build on PC and deploy, or build in the app": in-app config is the main path, and the developer path is an add-on that plugs in as a connector.

## 6. System flow: what sits where

```
Android App (Kotlin + Compose)
  - UI, mic/speaker, approvals, secure storage
        |  HTTPS + SSE (streaming) + FCM push
        v
Backend API (FastAPI, on free cloud host)
  - Auth check (JWT), agent CRUD, chat endpoint
  - Agent Runner: loads agent config, builds LLM + tools, runs loop
  - Tool Gateway: permission check, approval, token injection, logging
  - Scheduler: cron triggers
        |                         |
        v                         v
Database + Auth (Postgres)     Encrypted secrets
  agents, permissions, logs      OAuth tokens, LLM API keys
        |
        v
External APIs: GitHub, Google Tasks, Todoist, Telegram, ...
LLM providers: OpenAI, Anthropic (using the user's own key)
```

**Request path for "Show my open PRs":**

1. App sends text to `POST /agents/{id}/chat` with the user's JWT
2. Backend loads the agent config and LLM profile, decrypts the key in memory only
3. Agent Runner calls the LLM with the list of allowed tools
4. LLM asks for `github.list_pull_requests`
5. Tool Gateway checks the manifest, attaches the GitHub token, calls GitHub, logs the call
6. Result goes back to the LLM, which writes the answer, streamed to the app

The LLM and the agent code never see OAuth tokens. The gateway is the only component that touches them.

## 7. Tech stack

### 7.1 Android app

- Language and UI: Kotlin, Jetpack Compose, Material 3
- Architecture: MVVM with ViewModel and StateFlow
- DI: Hilt
- Networking: Retrofit, OkHttp, kotlinx.serialization
- Streaming replies: OkHttp SSE (or a WebSocket)
- Local storage: Room (cached agents and chats), DataStore (settings)
- Secrets on device: Android Keystore with EncryptedSharedPreferences (only for the session token)
- Sign in: Credential Manager (Google sign-in)
- OAuth for connectors: Custom Tabs plus a deep link back to the app (the backend handles the code exchange)
- Push: Firebase Cloud Messaging
- Background work: WorkManager
- Voice: `SpeechRecognizer` and `TextToSpeech` first, cloud STT later
- Biometric: AndroidX Biometric

### 7.2 Backend

- Language and framework: Python with FastAPI (best ecosystem for LLM tooling)
- Agent loop: LangChain and LangGraph, with `langchain-openai` (ChatOpenAI), `langchain-anthropic` (ChatAnthropic) and `langchain-mcp-adapters` for MCP tools
- Validation: Pydantic
- Scheduling: APScheduler in v1 (move to a proper job queue later)
- Crypto: `cryptography` library (AES-GCM) for encrypting secrets
- HTTP client: httpx, with SSRF protection (section 8.4)

### 7.3 Data and infrastructure (free-tier friendly)

- Database, auth, row-level security: Supabase (Postgres)
- Push: Firebase Cloud Messaging (free)
- Backend hosting: one of Google Cloud Run, Render, Fly.io or Railway. Free tiers change, so verify limits before choosing. Free hosts often sleep when idle, which adds a cold start of a few seconds.
- Cache and rate limiting: start in-memory, move to Upstash Redis if needed
- Master encryption key: environment secret in v1, a cloud KMS later
- CI/CD: GitHub Actions
- Monitoring: Sentry free tier for errors

## 8. Security plan

### 8.1 Permission manifest (per agent)

Every agent has a manifest like this, stored in the database:

```json
{
  "agent_id": "a1",
  "connectors": {
    "github": {
      "allow": ["pull_requests.read", "followers.read", "profile.write"],
      "approval_required": ["profile.write"]
    }
  },
  "limits": { "max_tool_calls_per_run": 10, "max_runs_per_day": 50 }
}
```

Anything not listed is denied by default.

### 8.2 Tool Gateway rules

- Every tool call passes through the gateway, no exceptions
- Checks: agent is active, tool is in the manifest, arguments are valid, limits not exceeded
- Write and dangerous tools pause for approval
- Attaches the token for that agent's own connection only
- Logs the call, arguments (secrets redacted) and result

### 8.3 Credentials

- Each agent gets its own OAuth grant with minimum scopes (GitHub fine-grained access or a GitHub App, Google incremental scopes)
- OAuth tokens and LLM API keys are encrypted at rest (AES-GCM), decrypted only in memory at call time
- The app can set a key but never read it back (it only sees the last 4 characters)
- Keys never appear in logs, error messages, or prompts
- Revoke: deleting an agent deletes its tokens and revokes the grant at the provider where possible

### 8.4 Custom base URL safety (important for the LLM settings)

Because users can enter any base URL, the backend could be tricked into calling internal addresses (SSRF). Rules:

- Require HTTPS
- Resolve DNS and block private, loopback and link-local ranges (including cloud metadata addresses)
- No redirects to blocked ranges
- Timeouts and response size limits

### 8.5 Data isolation

- Postgres row-level security: a user can only read their own rows
- Memory and logs are keyed by agent ID, and the runner only loads the current agent's data
- One agent can never read another agent's tokens or memory

### 8.6 Prompt injection

- Treat everything returned by tools (PR text, task titles, messages) as untrusted data
- Real protection comes from the gateway: least privilege, approval for writes, and limits, not from asking the model to be careful
- Show the user exactly what will be changed before approval

### 8.7 App and API security

- Short-lived JWT plus refresh token, over TLS only
- Rate limiting per user and per agent
- Play Integrity API check on sensitive endpoints
- No secrets in the APK
- Optional biometric lock
- Audit log visible to the user, and a kill switch that stops all agents instantly

### 8.8 Custom code safety (v2)

- Custom tools are remote MCP servers over HTTPS, called only through the gateway
- Their permissions are declared in a manifest that the user reviews at install
- If you ever host user code, run it in a sandbox (gVisor or Firecracker), with no network except allowed hosts

## 9. LLM configuration (ChatOpenAI-style)

Each LLM profile stores these fields:

| Field | Example |
| --- | --- |
| name | My Claude |
| provider | `openai`, `anthropic`, or `openai_compatible` |
| base\_url | `https://api.openai.com/v1` (optional for known providers) |
| api\_key | user's own key (encrypted) |
| model | `gpt-4o`, `claude-sonnet-4-5`, or any model name the user types |
| temperature | 0.2 |
| max\_tokens | 1000 |

Backend wrapper (one small factory function):

```python
from langchain_openai import ChatOpenAI
from langchain_anthropic import ChatAnthropic

def build_llm(p):
    if p.provider == "anthropic":
        return ChatAnthropic(model=p.model, api_key=p.api_key,
                             base_url=p.base_url or None,
                             temperature=p.temperature, max_tokens=p.max_tokens)
    # openai and any OpenAI-compatible endpoint
    return ChatOpenAI(model=p.model, api_key=p.api_key,
                      base_url=p.base_url or None,
                      temperature=p.temperature, max_tokens=p.max_tokens)
```

Notes:

- Because `openai_compatible` accepts any base URL, users can later plug in Gemini, Groq, OpenRouter, or local models with no new code
- Model names change often, so let users type any model name, and optionally fetch the provider's model list for a dropdown
- Anthropic is called natively (not through a compatibility layer) so tool calling works fully
- LiteLLM is an alternative if you want one library for 100+ providers later
- Tradeoff to tell users: since agents run in the cloud, their key is stored encrypted on your backend. Say this clearly in the UI

## 10. Backend API (draft)

| Endpoint | Purpose |
| --- | --- |
| `POST /auth/google` | Exchange Google ID token for a session |
| `GET/POST /llm-profiles` | List and create LLM profiles |
| `POST /llm-profiles/{id}/test` | Test connection |
| `GET/POST /agents` | List and create agents |
| `PATCH/DELETE /agents/{id}` | Update, pause, delete |
| `GET /connectors` | Available connectors and scopes |
| `GET /oauth/{connector}/start` | Begin OAuth, returns the auth URL |
| `GET /oauth/{connector}/callback` | Backend receives the code, stores tokens |
| `POST /agents/{id}/chat` | Send message, streams reply (SSE) |
| `GET /approvals` | Pending approvals |
| `POST /approvals/{id}/decision` | Approve or reject |
| `GET /agents/{id}/logs` | Activity log |
| `POST /agents/{id}/revoke` | Kill switch for one agent |
| `POST /devices` | Register FCM token |

## 11. Database tables (draft)

- `users`
- `llm_profiles` (user\_id, provider, base\_url, model, encrypted\_key, params)
- `agents` (user\_id, name, instructions, llm\_profile\_id, status, triggers)
- `connections` (agent\_id, connector, encrypted\_tokens, scopes)
- `permissions` (agent\_id, tool, level: allow / ask / deny)
- `messages` (agent\_id, role, content, created\_at)
- `memories` (agent\_id, key, value)
- `approvals` (agent\_id, tool, args, status, expires\_at)
- `audit_logs` (agent\_id, tool, args\_redacted, result\_summary, created\_at)
- `devices` (user\_id, fcm\_token)

## 12. Voice plan

- v1: Android `SpeechRecognizer` and `TextToSpeech`, tap-to-talk, languages: English (India) and Hindi
- v1.5: cloud speech-to-text (for example Sarvam, Deepgram or Whisper) if Hinglish and technical terms are misheard
- Later: real-time streaming voice and optional wake word
- Safety: show transcript first, voice can start a request but cannot approve a risky one

## 13. Roadmap

**Phase 1 (core, about 3 to 4 weeks)**

- Sign in, LLM profiles, agent list, chat with streaming
- GitHub connector (read-only first)
- Tool Gateway with permission checks

**Phase 2 (security and usability)**

- Approvals with FCM push, activity log, kill switch
- Write actions (profile edits), To-do connector
- Voice (built-in Android)

**Phase 3 (growth)**

- Scheduled agents, Telegram, Notion, Calendar
- Cloud speech-to-text

**Phase 4 (v2)**

- Custom MCP connectors, templates marketplace, cost dashboard

## 14. Risks to keep in mind

- Free cloud tiers sleep or have limits, so scheduled agents may be delayed until you move to a paid tier
- Storing users' LLM keys makes the backend a high-value target, so encryption and access control matter from day one
- Google verification is required for Gmail scopes, which can take time, so start with Tasks and Calendar
- API terms and free tiers change, so re-check each provider before launch
- LLM cost is paid by the user (their own key), but add per-run limits to protect them from runaway loops

## 15. Questions for you

1. Is the v1 connector list right (GitHub, Google Tasks, Todoist, Telegram)?
2. Should LLM keys live on your backend (needed for scheduled agents) or on the device only (more private, but agents then run only when the app is open)?
3. Do you want Flutter instead of native Kotlin, or stay with native Android?
4. Which free host do you prefer for the backend?
5. Anything to add, remove or reorder?

## 16. Development workflow: browser-based AI sandbox route (no setup required)

If you don't want to install complex coding software on your PC, you can use online AI development platforms.

**Tools to use:** IDX (by Google) or Bolt.new.

**The real-time loop:**

1. You open these tools in your PC's browser. They feature built-in AI assistants powered by models like Claude.
2. They provide a live interactive mobile preview right on your PC screen.
3. To see it on your actual phone, they generate a private web link or QR code. You open that link on your mobile browser, and as the AI alters the code on your PC, the phone screen updates automatically.

*Note to verify before relying on this: tool names and features change often (IDX has been rebranded under Firebase Studio), and browser tools like Bolt.new mostly produce web or React Native/Expo apps, not native Kotlin. Check this against the Android stack in section 7.*
