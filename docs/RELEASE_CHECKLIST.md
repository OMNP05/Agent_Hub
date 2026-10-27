# Release checklist (Android app)

Status as of Phase 10. "Verified" means a command was run and passed in the build environment; everything else is listed under **Not verified** and must be checked before a public release.

## Verified automatically

| Check | Command | Result |
|---|---|---|
| Debug build | `./gradlew assembleDebug` | passes |
| Release build with R8 shrinking | `API_BASE_URL=https://... ./gradlew assembleRelease` | passes, produces an unsigned APK of about 3 MB |
| Lint | `./gradlew lintDebug` | passes, 0 errors |
| Unit tests | `./gradlew testDebugUnitTest` | 292 tests, 0 failures |
| No secrets in sources | `NoSecretsInSourceTest` | no credential-shaped strings; no `SharedPreferences`, `Log.*`, `println` in production code; backup off; cleartext only in debug; no `google-services.json`, `keystore`, `local.properties` committed |
| Voice can never approve | `VoiceCannotApproveTest` | voice package has no reference to the approvals code |
| Release forces HTTPS | `app/build.gradle.kts` | release build fails if `API_BASE_URL` is not `https://` |
| R8 keeps JSON models | `mapping.txt` | all 16 DTO classes and their `$$serializer` classes are kept |

## Not verified (needs a device, backend or credentials)

1. **The app has never run on an emulator or phone.** Layouts, animations, navigation, dark/light themes, rotation, and the font-scale and screen-reader experience are untested visually. Instrumented and Compose UI tests do not exist yet.
2. **A release APK was built but never launched.** R8 can break reflection at run time even when the build passes. Launch the release build and run sign-in, chat and approval once before shipping.
3. **No backend exists.** Every endpoint is a proposal in `API_CONTRACT.md`. Sign-in, OAuth connect, SSE chat, approvals, devices, LLM profiles and account deletion have been exercised only against a mock server in unit tests.
4. **Google sign-in** needs `GOOGLE_WEB_CLIENT_ID` in `local.properties` and a Google Cloud project.
5. **App Links** need a domain, `APP_LINK_HOST`, and `/.well-known/assetlinks.json` with the release signing certificate fingerprint. The default host `agenthub.invalid` can never verify.
6. **Push** needs a Firebase project and `app/google-services.json` (gitignored). Without it the app builds and push is off.
7. **Release signing** is not configured: the APK is unsigned. Create a keystore outside the repository and wire it in through `keystore.properties` (gitignored).
8. **Speech recognition and read-aloud** (English India, Hindi) have never run on a device.
9. **Biometric lock** has never run on a device.
10. **Provider approvals** (Google OAuth verification, Gmail restricted-scope assessment, GitHub App registration) are outside this app; see `architecture.md` section 2.5.

## Known limitations

- Offline cache covers agents, conversations and messages. Approvals, the activity log and LLM profiles are fetched live.
- The notification switch is honoured in the app (a switched-off device shows no approval notification), but the server still sends pushes; there is no server-side per-device opt-out.
- The Calendar and Gmail agents depend on backend Gateway rules the app only mirrors for display. The Gateway is the real enforcement.
- No instrumented tests, screenshot tests or baseline profile.
