# Android architecture (proposal for architecture.md section 9)

Status: **proposal**; `architecture.md` section 9 is not written. Needs user confirmation before it is copied there.

## Stack (from CLAUDE.md)
Kotlin, Jetpack Compose, Material 3, MVVM (ViewModel + StateFlow), Hilt (KSP), Retrofit/OkHttp (+ OkHttp SSE), kotlinx.serialization, Compose Navigation, Credential Manager, Custom Tabs, DataStore, Room (cache), WorkManager only if needed, FCM, AndroidX Biometric.

## Layers
```
ui (Compose screens, ViewModels, UiState/Effect)
  -> domain (models, repository interfaces; no Android deps)
    -> data (Retrofit API, SSE client, Room, DataStore, mappers)
```
Single `:app` module, packages by feature: `auth`, `agents`, `chat`, `approvals`, `activity`, `connectors`, `settings`, `voice`, `core` (network, storage, theme, ui components, error types).

## Conventions
- One immutable `UiState` per screen, a `Channel` for one-shot effects, as in the android-dev skill.
- Repositories are the error boundary: exceptions become sealed `DataError` mapped from backend error codes.
- Network: `AuthInterceptor` adds the bearer token; an `Authenticator` refreshes once on 401. Host fixed by `BuildConfig`.
- SSE: events from architecture 3.10, each with an id; reconnect with `Last-Event-ID`; fallback `GET /runs/{id}`.
- Template catalog is bundled as a static list of exactly three entries mirroring architecture 2.7 (toggles, tools, risk). Risk levels are constants in the app for display only; the backend Gateway is the enforcement.
- Navigation: top-level tab graph per tab (state saved), chat/approval/agent detail as nested destinations.

## Security in the app
No provider tokens or LLM keys stored; LLM key is sent once and never read back (last 4 shown). Session in Keystore-encrypted DataStore. `allowBackup=false`. Cleartext only in debug. No logging of bodies.
