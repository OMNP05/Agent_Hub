# Requirements (Android app)

Derived from `CLAUDE.md`, `architecture.md` 1 to 3 and the blueprint. Locked decisions live in `CLAUDE.md`; this file does not change them.

## Functional

| ID | Requirement | Phase |
|---|---|---|
| F1 | Sign in with Google (Credential Manager, nonce), session stored encrypted | 3 |
| F2 | Home lists the user's agents; empty state offers **New Agent** | 5 |
| F3 | New Agent shows exactly three templates: GitHub, Gmail, Calendar. No Custom/Other | 5 |
| F4 | Per-agent toggles from the template; Read on by default as specified; Write = Ask/Allow; Dangerous = Ask only | 5 |
| F5 | Connect via backend OAuth in a Custom Tab; return by verified App Link; show connection status/granted scopes | 5 |
| F6 | Edit name, instructions, LLM profile; deploy, pause, delete; global kill switch | 5, 9 |
| F7 | Chat per agent, streaming via SSE, tool cards, stop, "busy" handling, New chat, history list/delete | 6 |
| F8 | Approval cards show exact action and full content; approve/reject by tap only | 7 |
| F9 | Activity tab: pending approvals and audit log | 7 |
| F10 | Voice: transcript shown before sending; short spoken replies; never approves | 8 |
| F11 | LLM profiles: provider (OpenAI, Anthropic, OpenAI-compatible), base URL, key (write-only), model free text, temperature, max tokens, test, default | 9 |
| F12 | Settings: theme (dark/light/system), biometric lock, notifications, export and delete account, sign out | 9 |
| F13 | Push (FCM) for approvals and scheduled results; device registration | 7 |
| F14 | Navigation: Home / Activity / Connectors / Settings, mini-bar with mic, animated transitions | 2 |

## Non-functional

- Every screen has loading (skeleton), empty and error states; errors show friendly codes, never raw bodies.
- UI state in ViewModels (StateFlow), survives rotation; no business logic in composables.
- Dark and light themes everywhere; own accent colour, logo and icons (no Spotify assets).
- Accessibility: content descriptions, 48dp touch targets, readable contrast.
- No secrets in APK or repo; no tokens, keys, email bodies or full tool results in logs.
- Release builds require HTTPS; cleartext allowed in debug only.

## Acceptance (global)

Matches `CLAUDE.md` definition of done: tests pass, no secrets in code/logs/app, the three-agent catalog is not extensible from the app, Dangerous tools can never be set to Allow, voice cannot approve.
