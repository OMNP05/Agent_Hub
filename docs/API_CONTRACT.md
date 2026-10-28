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
