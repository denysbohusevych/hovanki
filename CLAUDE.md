# CLAUDE.md

Hovanki: street hide-and-seek for Android + iOS. Kotlin Multiplatform + Compose Multiplatform client, Spring Boot server, shared Kotlin module for protocol and rules. Docs are in Russian (`docs/`), code and comments in English.

Read first: `docs/architecture.md` (how it works), `docs/adr/` (why: 0001 stack and game rules, 0002 session storage), `docs/roadmap.md` (what is missing).

## Layout

- `shared/` — KMP (jvm, android, iosArm64, iosSimulatorArm64). `protocol/` DTOs + `ApiRoutes` + `protocolJson`, `totp/` catch codes (pure-Kotlin SHA-1/HMAC), `geo/`, `rules/` (`LocationTrack`, `ZoneRules`, `CatchRules`, zone schedule).
- `server/` — Spring Boot. `api/` thin controller + error mapping + bearer auth, `game/` domain (`Game`), `GameService` (locking), `GameRegistry` (in memory), `GameJanitor` (deletes old games).
- `clientCore/` — KMP (jvm, android, iosArm64, iosSimulatorArm64), client logic without UI: Ktor `GameApi`/`HttpGameApi`, `GameConnection`/`PollingGameConnection`, `LocationOutbox`, `ServerClock`, `GameSessionManager` (saves the session and resumes it after a restart, `ClientStorage`), `LocationProvider`/`BackgroundTracker`/`SecureStore` interfaces. No Compose, no platform code; the JVM target is for headless e2e bots.
- `composeApp/` — KMP library (`com.android.kotlin.multiplatform.library`): Compose UI, Koin, Ktor engines, platform services (`LocationProvider`, `BackgroundTracker`, `SecureStore` — Android Keystore / iOS Keychain, `ProximityScanner`, `CatchCodeScanner`).
- `androidApp/` — thin Android entry point (`Application`, `MainActivity`).
- `e2e/` — JVM end-to-end tests: `BotPlayer` runs `:clientCore` (Ktor/OkHttp) on a simulated phone (`FakeGps` + `Route`/`GpsNoise`, `DeviceClock`, `FakeNetwork`); `Scenario` DSL; `Observer` reads the server's debug endpoint (Spring profile `e2e` only). Tests start the server in-process on a random port; `HOVANKI_E2E_SERVER_URL` targets an external one. Device layer: `e2e/run-devices.sh` builds and starts the server, emulators/simulators and the debug app, then `e2e devices` (`src/main/.../devices/`) drives them with Maestro flows (`e2e/maestro/`) mixed with bots. See `docs/e2e.md`.
- `iosApp/` — Xcode project, SwiftUI shell around `MainViewControllerKt.mainViewController()`; see `iosApp/README.md`. Settings in `Configuration/Config.xcconfig` (fixed bundle id `app.hovanki.ios`), per-machine overrides in git-ignored `Local.xcconfig`.
- `deploy/` — server deployment: `compose.yaml` (server + Caddy for TLS) and `aws-user-data.sh` for one EC2 VM; see `docs/deploy.md`.
- Versions: only in `gradle/libs.versions.toml`. Modules reference each other via typesafe accessors (`projects.shared`).

## Commands

```bash
./gradlew spotlessApply                    # format (ktlint); CI runs spotlessCheck
./gradlew check                            # all tests + checks available on this OS, without the e2e scenarios
./gradlew :shared:jvmTest :clientCore:jvmTest :server:test   # fast feedback loop
./gradlew :e2e:test                        # e2e scenarios: bots play whole games (~3 min); reports in e2e/build/reports/e2e/
e2e/run-devices.sh --android 2 --bots 3    # app on 2 emulators + bots (needs KVM + Maestro; --ios 1 on macOS); ~15 min in CI
./gradlew :server:bootRun                  # server on :8080, health at /actuator/health
./gradlew :androidApp:installDebug         # Android debug build
./gradlew :androidApp:assemblePreview      # tester build (release code, app.hovanki.preview); CI: preview.yml
./gradlew :shared:iosSimulatorArm64Test    # macOS only
```

CI on push (`ci.yml`) is fast: spotlessCheck, `check`, Android and iOS builds. `check` does not depend on `:e2e:test`; the e2e bots and the device layer run only nightly and on demand (`.github/workflows/nightly.yml`, Actions → Nightly → Run workflow on any branch, `suite` = all / bots / devices). A failed scheduled run opens an issue labelled `nightly-failure`.

iOS (framework, app, simulator tests) builds only on macOS with Xcode 26.4+; on Linux iOS targets are skipped (`kotlin.native.ignoreDisabledTargets=true`). Don't try to fix iOS-only failures blind — say so.

## Conventions

- **Shared rules live in `:shared`** and are used by both client and server: thresholds (`GameRules`), zone math (`ZoneSchedule.stateAt`), GPS filtering, TOTP. Never re-implement them on one side.
- **Server is authoritative.** It decides phases, catches and visibility; `Game.snapshotFor(viewer)` never includes positions the viewer may not see. The client only renders and hints.
- **Protocol changes must stay backward compatible**: add fields with default values; never rename or remove fields, enum values or routes. A new enum value breaks old clients when the property has no default (e.g. `VisibleLocation.reason`) — add a new defaulted field instead, or a new API version.
- **All timestamps are server time** (epoch millis). The client converts via `ServerClock`; never use the device clock for protocol data, deadlines or TOTP.
- **`Game` is a pure domain object**: no Spring, no threads, time passed in as `nowMillis`. Time-based transitions go into `advance(now)`; no background tickers. Access goes through `GameService.update(...)` (lock + advance + snapshot).
- **No platform code in `commonMain`.** Platform services are interfaces in `commonMain`, implemented in `androidMain`/`iosMain`, bound via Koin. `expect`/`actual` only for small glue.
- **GDPR:** location data stays in memory and is deleted with the game; never log coordinates or tokens. The device stores only the session (token) and the start screen's fields, through `ClientStorage`/`SecureStore` (Keystore/Keychain, `docs/adr/0002-session-storage.md`); never store coordinates on the device.
- **Debug/test hooks never reach production:** the observer endpoint exists only with the Spring profile `e2e`; keep `DebugEndpointAbsentTest` green. Test tags (`TestTags`) and `LaunchOptions` must not change release behavior: launch parameters are read only in debug builds. The Android `preview` build type (tester builds, `preview.yml`) is release plus its own application id and shares the no-op twins in `androidApp/src/release`. iOS dev exceptions (local-network ATS) are added to Debug only by `iosApp/Configuration/debug-info-plist.sh`.
- **Build-time settings** come from Gradle properties via the generated `BuildConstants` in `:composeApp` (`hovanki.serverUrl`: default server of non-debug builds, https only). Don't hardcode server addresses; non-debug builds talk HTTPS only.
- **Run the long tests yourself before a PR** (CI on push doesn't): changed rules, protocol or client–server behavior → `./gradlew :e2e:test` (~3 min, works in a cloud container without KVM); changed UI or platform code (`:composeApp`, `androidApp`, `iosApp`, Maestro flows) → trigger `nightly.yml` manually on your branch (`suite=devices`, only the scenario you need). Say in the PR what you ran. See `docs/ci-cd.md`.
- **Tests next to the code:** `shared/src/commonTest` and `clientCore/src/commonTest` (run on JVM and iOS), `shared/src/jvmTest` for JDK cross-checks, `server/src/test` (`GameTest` for rules with an explicit `now`, `GameApiTest` for HTTP round trips with MockMvc). New rule → unit test in `GameTest` or `commonTest`, and an e2e scenario in `e2e/src/test` when it spans client and server. A bug found by an e2e scenario is fixed in the game or server with a test, never worked around in the scenario.
- **Style:** ktlint `intellij_idea`, max line 120, trailing commas (see `.editorconfig`). Run `./gradlew spotlessApply` before committing.
- **Gradle:** configuration cache is on — no configuration-time side effects; no hardcoded versions in build scripts.

## AGP 9 / KMP structure

- Never apply `org.jetbrains.kotlin.android` (kotlin-android) in `androidApp`: AGP 9 has built-in Kotlin.
- Never add `androidTarget()` in KMP modules: they use `com.android.kotlin.multiplatform.library` and the `kotlin { android { ... } }` block.
- Android/shared bytecode is JVM 17, the server toolchain is JDK 21.

## When you change things

- New endpoint: `ApiRoutes` + DTO in `:shared` → `Game`/`GameService` + controller in `:server` → `GameApi` + `GameSessionManager` in `:clientCore` → UI in `:composeApp` → API table in `docs/architecture.md`.
- Behavior, commands or setup changed → update `README.md` / `docs/`. Big decisions → new ADR in `docs/adr/NNNN-*.md`.
- Recipes for endpoints, platform services and screens: `docs/architecture.md`, section «Как добавить…».
