# API contract (Android view, proposal)

Status: **proposal**, not agreed with the backend (architecture section 7 is not written). Endpoints come from blueprint section 10 and architecture 1 to 3. All calls send `Authorization: Bearer <JWT>`; user identity is never in the body.

| Endpoint | Purpose |
|---|---|
| `POST /auth/google` | `{id_token, nonce}` -> `{access_token, refresh_token, expires_in}` |
| `POST /auth/refresh`, `POST /auth/logout` | rotate / revoke |
| `GET/POST /llm-profiles`, `POST /llm-profiles/{id}/test` | key write-only; responses expose `key_last4` |
| `GET/POST /agents`, `PATCH/DELETE /agents/{id}`, `POST /agents/{id}/revoke` | agent CRUD, pause, kill switch |
| `GET /oauth/{connector}/start?agent_id=` | returns `{auth_url}`; connectors: `github`, `gmail`, `calendar` |
| `POST /agents/{id}/chat` | SSE stream |
| `GET /runs/{id}`, `POST /runs/{id}/cancel` | catch-up and stop |
| `GET /approvals`, `POST /approvals/{id}/decision` | `{decision: approve|reject}` |
| `GET /agents/{id}/logs` | activity log |
| `POST /devices` | FCM token |

Error body: `{"error": {"code": "llm_key_invalid"}}`; the app maps codes to messages. Unknown codes show a generic message.

## Chat and runs (proposal)

- `POST /agents/{id}/chat` body `{"conversation_id": "...", "message": "..."}`, response `text/event-stream`. A second message while a run is active returns `409 {"error":{"code":"agent_busy"}}`.
- `GET /runs/{id}/events` with header `Last-Event-ID` resumes the stream after a dropped connection.
- `GET /runs/{id}` returns `{"id", "status", "error_code"?}`; `status` is one of `queued, running, awaiting_approval, completed, failed, cancelled, expired`.
- `POST /runs/{id}/cancel` stops a run.
- `GET /agents/{id}/conversations`, `POST /agents/{id}/conversations` (New chat), `DELETE /agents/{id}/conversations` (clear history), `DELETE /conversations/{id}`, `GET /conversations/{id}/messages?after_seq=`.

Each SSE message has an increasing `id:`, an `event:` name and a JSON `data:` line (architecture 3.10):

| event | data |
|---|---|
| `run.started` | `{"run_id", "conversation_id"}` |
| `message.delta` | `{"text"}` |
| `tool.started` | `{"tool_call_id", "tool"}` |
| `tool.finished` | `{"tool_call_id", "tool", "summary", "ok"}` (summary is the trimmed text, never raw provider data) |
| `approval.required` | `{"approval_id", "tool"}` (stream ends; the run waits in the database) |
| `run.completed` | `{"message_id"}` |
| `run.error` | `{"code"}` |

Stored message `content` is an array of blocks: `{"type":"text","text"}`, `{"type":"tool_call","id","tool","args"}`, `{"type":"tool_result_summary","tool_call_id","summary"}`.

## Approvals, devices and push (proposal)

- `GET /approvals` returns pending approvals: `{"id","run_id","agent_id","agent_name","tool","args","status","created_at","expires_at"}`. `args` are the exact stored arguments shown in full on the card.
- `POST /approvals/{id}/decision` body `{"decision":"approve"|"reject"}`. The server changes `pending` to the new status in one atomic update and runs the stored arguments only. A second decision, or one after expiry, returns `409 {"error":{"code":"approval_expired"}}`. The app never retries a decision automatically.
- `GET /agents/{id}/logs?before=<ISO time>&limit=30` returns audit entries newest first: `{"id","agent_id","tool","args_redacted","result_summary","created_at"}`. Secrets are redacted by the server.
- `POST /devices` body `{"fcm_token"}` registers this phone; `POST /devices/unregister` with the same body is called on sign-out.
- FCM data message for an approval (no action details, so nothing sensitive reaches the lock screen): `{"type":"approval","approval_id":"...","agent_name":"..."}`. Tapping it opens the Activity tab on that approval.


## LLM profiles, safety and account (proposal)

- `GET /llm-profiles` returns `{"id","name","provider","base_url","model","temperature","max_tokens","key_last4","is_default"}`. **The API key is never returned**, only `key_last4`. Providers: `openai`, `anthropic`, `openai_compatible`.
- `POST /llm-profiles` and `PATCH /llm-profiles/{id}` take the same fields plus `api_key`. On `PATCH`, an omitted `api_key` keeps the stored key. The server must repeat all validation and apply SSRF protection to `base_url` (https only; block private, loopback and link-local ranges including cloud metadata; no redirects into blocked ranges; timeouts and size limits).
- `POST /llm-profiles/{id}/test` tests a saved profile; `POST /llm-profiles/test` tests an unsaved one from the request body. Both return `{"ok": true|false}`.
- `DELETE /llm-profiles/{id}` deletes a profile and its stored key.
- `POST /agents/kill-switch` pauses every agent; runs stop at their next Gateway call.
- `POST /account/export` returns `{"url"}` (https) or an empty url if the export is prepared asynchronously. `DELETE /account` removes the user, agents, connections (revoked at providers first), keys and logs.


## Authentication (implemented in backend phase B3)

- `POST /auth/google` body `{"id_token", "nonce"}` (extra fields are rejected with 422) returns `{"access_token", "refresh_token", "expires_in"}`. The backend forwards the Google ID token and the raw nonce to Supabase Auth, which verifies signature, issuer, audience, expiry and nonce. A rejected token is `401 unauthorized`; Supabase being down is `503 auth_unavailable`; more than 10 attempts a minute from one address is `429 rate_limited`.
- `POST /auth/refresh` body `{"refresh_token"}` returns the same shape. Refresh tokens rotate; a reused or revoked one is `401 unauthorized` and the app must sign the user out.
- `POST /auth/logout` (Bearer token) returns `204` and revokes every session of the user ("sign out everywhere"). The app clears its own copy regardless of the result.
- `GET /me` (Bearer token) returns `{"id"}` taken from the verified token only.
- Every authenticated route checks the access token locally against the project's public signing keys (ES256 or RS256 only; HS256 and `none` are refused). Missing, malformed, expired and forged tokens all give the identical `401 {"error":{"code":"unauthorized"}}`, with no reason.
- The app must set Supabase's Google provider **Client IDs** to the Web client ID (the same one the Android app uses as its token audience) and use a nonce: Google gets the SHA-256 hex of the nonce, and this endpoint gets the raw nonce.


## LLM profiles (implemented in backend phase B5)

- `GET /llm-profiles` returns the caller's profiles. `POST /llm-profiles` returns `201` with the profile; `api_key` is required on create. `PATCH /llm-profiles/{id}` takes the same fields; an omitted or empty `api_key` keeps the stored key. `DELETE /llm-profiles/{id}` returns `204` and removes the stored key; agents that used it fall back to no profile.
- Bodies reject unknown fields (`user_id`, `key_last4` and so on) with `422 invalid_request`. Limits: name 1 to 60 characters, model 1 to 200, temperature 0 to 2, max_tokens 1 to 200000, API key 8 to 512 printable characters without spaces.
- `base_url` must be `https`, have no credentials, query or fragment, and must not be an IP address in a private, loopback, link-local or metadata range, `localhost`, or an internal name. An invalid one is `422 invalid_request`. `openai_compatible` requires it. A trailing slash is removed.
- The first profile a user creates is the default. At most one profile is the default; choosing another moves it, and unticking the default flag on the default profile does not remove it. At most 20 profiles per user (`409 profile_limit_reached`).
- Another user's profile is `404 not_found`, identical to one that does not exist.
- `POST /llm-profiles/{id}/test` and `POST /llm-profiles/test` make one authenticated `GET /models` call to the provider (no tokens are spent). Result `{"ok": true}`. A key the provider rejects (401 or 403) is `400 llm_key_invalid`. A network problem, a provider outage, a rate limit or an unusual gateway is `{"ok": false}`, never `llm_key_invalid`. A stored key that can no longer be decrypted is also `llm_key_invalid` (the user enters it again). At most 10 tests a minute per user (`429 rate_limited`).
- Every outbound call to a provider goes through one guarded client: the destination address is checked after DNS resolution and the connection is made to that checked address (DNS rebinding cannot swap it), redirects are never followed, responses are capped at 8 MB (2 MB for the key check), and compressed responses are refused.


## Agents and the catalog (implemented in backend phase B6)

- `GET /agents`, `GET /agents/{id}`, `POST /agents` (`201`), `PATCH /agents/{id}`, `DELETE /agents/{id}` (`204`), `DELETE /agents/{id}/connection` (`204`), `POST /agents/kill-switch` (`204`), `GET /agent-templates`.
- An agent is `{id, name, template_id, connector, instructions, status, llm_profile_id, time_zone, toggles, approval_modes, connection, last_activity_at}`. `connection` is `null` or `{status, scopes_granted}`; provider tokens are never returned.
- **Create** takes `template_id`, `name` and optionally `instructions`, `toggles`, `approval_modes`, `llm_profile_id`, `time_zone`. The `connector` is derived from the template and the new agent is always `draft`; both fields (and `user_id`, `id`, `status`) are refused with `422 invalid_request`. Omitted `instructions` take the template default.
- **The server catalog decides what is valid.** `template_id` must be one of `github-assistant`, `gmail-assistant`, `calendar-assistant`. A toggle or tool that is not in that template, a mode for a Read tool, a value that is not exactly `true`/`false` for a toggle, or a mode other than `ask`/`allow` is `422 invalid_request` (the request is refused, never half-applied). Missing toggles take their default (only Read toggles default on). Write tools default to `ask`.
- **A Dangerous tool can never be `allow`:** a request that sets one to `allow` is accepted but stored and returned as `ask`.
- **Update** changes only the fields that are present. `toggles` and `approval_modes` are merged onto the stored values and then validated again as a whole. An explicit `null` for `name`, `instructions`, `toggles`, `approval_modes` or `status` is ignored; `llm_profile_id: null` clears the profile. `status` may only be `active` or `paused`; `active` needs an active connection, otherwise `409 connection_needs_reauth`.
- `llm_profile_id` must be one of the caller's own profiles, otherwise `422 invalid_request`.
- Names are 1 to 60 single-line characters, instructions up to 2000 characters, `time_zone` an IANA name such as `Asia/Kolkata`. Control characters (including NUL) are refused. At most 20 agents per user (`409 agent_limit_reached`).
- Another user's agent is `404 not_found`, identical to one that does not exist.
- `DELETE /agents/{id}` removes the connection and its encrypted tokens, conversations, messages, runs, approvals and schedules. `DELETE /agents/{id}/connection` removes the grant and tokens and returns the agent to `draft`. The provider is asked to revoke the agent's tokens first (best effort: an unreachable provider never blocks the user).
- `POST /agents/kill-switch` pauses every agent of the caller at once and is safe to repeat; running work stops at its next Gateway call (B8). Limited to 10 a minute.
- `GET /agent-templates` returns the server catalog (`id`, `connector`, `default_instructions`, and each toggle with its tools and risk `read`/`write`/`dangerous`) so a client can check its own copy. A test keeps the Android catalog identical to it.


## Connecting providers (implemented in backend phase B7)

- `GET /oauth/{github|gmail|calendar}/start?agent_id=` (Bearer) returns `{"auth_url"}` for a Custom Tab. The agent must be the caller's own (`404 not_found` otherwise) and its connector must match the path (`422`). Gmail and Calendar ask only for the scopes the agent's enabled toggles need (`422` if none are on); GitHub Apps carry their permissions on the App. The URL has a single-use `state` (10 minutes) and a PKCE S256 challenge; the verifier stays on the server, encrypted. Starting again voids the agent's earlier link. `503 oauth_not_configured` if the provider's settings are missing; at most 20 starts a minute per user.
- `GET /oauth/{connector}/callback?code=&state=` is called by the browser, not the app, and needs no login: the `state` is the proof. It always answers `302` to `{PUBLIC_BASE_URL}/oauth/done?status=ok|error&agent_id=...` and never echoes provider text, the code or the state. A forged, unknown, reused, expired or wrong-connector state is `status=error` with no `agent_id`. A denied consent or a failed code exchange is `status=error` with the agent id, and the state is already used up. For Google, a grant with no refresh token or no scopes is refused. The scopes stored on the connection are the ones Google reports as actually granted, not the ones requested.
- `GET /oauth/done` is a static page. The Android App Link (`https://{APP_LINK_HOST}/oauth/done`) normally opens the app instead; the app reads `status` and `agent_id`.
- `GET /.well-known/assetlinks.json` publishes `ANDROID_PACKAGE` and `ANDROID_CERT_SHA256` for App Link verification, and `404` if no fingerprint is configured.
- A successful connect makes a `draft` agent `active`. Reconnecting replaces the grant.
- **Server-side token use (for the Tool Gateway, B8):** the access token is refreshed when it expires within 60 seconds, under a per-connection lock so two calls make one refresh. GitHub's rotating refresh token is saved with each refresh. A rejected refresh (revoked, expired) sets the connection to `needs_reauth` and the call fails with `409 connection_needs_reauth`; a provider outage fails with `503 provider_unavailable` and does not change the connection.
- Disconnect and delete ask the provider to revoke first. Google revokes a user's whole grant for the project, so a Google token is revoked only when the agent is the user's last Google agent; otherwise it is only forgotten locally.
- Provider calls are restricted to `github.com`, `api.github.com`, `accounts.google.com` and `oauth2.googleapis.com`.
