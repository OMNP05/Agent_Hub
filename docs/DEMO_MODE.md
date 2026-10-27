# Demo mode (debug builds only)

Debug builds run against a **built-in fake server** so you can click through the whole app without a backend. It is not the real Tool Gateway and proves nothing about the real backend; every name, email and number it returns is made up.

## Run it

1. Build and install the debug app: `./gradlew installDebug` (or run it from Android Studio).
2. Open **Agent Hub** and tap **Continue with Google**. Sign-in is faked and instant.
3. You land on Home with one seeded agent, **Demo GitHub**, and one LLM profile.

To use a real backend instead, add `DEMO_BACKEND=false` to `android/local.properties` and set `API_BASE_URL`.

## Things to try

| Say or do | What you should see |
|---|---|
| GitHub agent: "Show my open PRs" | tool card "Fetched 3 open PRs", then a streamed answer |
| GitHub agent: "Change my bio to: Android engineer" | "needs your approval", then a card in **Activity** with the exact bio and Approve / Reject |
| Tap **Approve** in Activity, go back to the chat | "Updated your profile" card and "Done." (history reloads when the chat comes back into view) |
| New agent -> **Gmail Assistant**, switch on **Send email**, create and connect | consent is granted instantly |
| Gmail agent: "Send an email to sam@example.com saying I'll join at 5" | approval card with recipient, subject and full body; Approve asks once more because sending is Dangerous |
| Gmail agent: "Any unread mail?" | tool card and a list of 4 made-up emails |
| Calendar agent: "Delete my team sync" | Dangerous approval; "What's on my calendar tomorrow?" runs directly |
| Ask for a tool whose switch is off | the agent refuses and names the missing permission |
| **Settings** -> LLM profiles | list, add (a key ending in `bad` is rejected), test, delete |
| **Settings** -> Pause all agents | every agent becomes Paused |
| **Settings** -> Delete account | everything is emptied |

The demo applies the same permission rules as the real design: a tool must be switched on, Write tools follow Ask or Allow, and Dangerous tools always ask. It never lets an approval run twice.

## How it works

`DemoBackend` is an OkHttp interceptor in `app/src/debug/`. The real app code (Retrofit, the SSE client, repositories, caches, ViewModels, screens) runs unchanged against it. It is bound through `DebugHooks`, which release builds never provide, so **no demo code is in the release APK** (checked by scanning the release dex and R8 mapping).

The same backend powers `EndToEndFlowTest` (31 tests: sign-in, create and connect an agent, streaming chat, approvals, activity log, LLM profiles, kill switch, account deletion) and `DemoScriptsTest`, which run on the JVM with streaming pauses switched off.

## Limits

- State lives in memory and resets when the app process is killed.
- Replies are canned and keyword-based; it is not an LLM.
- Voice, push notifications and the biometric lock are not part of the demo and need a device with those features set up.
