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

SSE events: `run.started`, `message.delta`, `tool.started`, `tool.finished`, `approval.required`, `run.completed`, `run.error` (architecture 3.10). Error body: `{"error": {"code": "llm_key_invalid"}}`; the app maps codes to messages.
