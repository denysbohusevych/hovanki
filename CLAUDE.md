# CLAUDE.md

Hovanki: street hide-and-seek for Android + iOS. Kotlin Multiplatform + Compose Multiplatform client, Spring Boot server, shared Kotlin module for protocol and rules. Docs are in Russian (`docs/`), code and comments in English.

Read first: `docs/architecture.md` (how it works), `docs/adr/` (why: 0001 stack and game rules, 0002 session storage, 0003 map and buildings, 0004 accounts, friends, groups and chat; 0005 is a draft for the future: game modes and other games on one platform, not implemented), `docs/roadmap.md` (what is missing).

## Layout

- `shared/` — KMP (jvm, android, iosArm64, iosSimulatorArm64). `protocol/` DTOs + `ApiRoutes` + `protocolJson` (games, `Accounts.kt`, `Social.kt`, chat), `totp/` catch codes (pure-Kotlin SHA-1/HMAC), `geo/`, `rules/` (`LocationTrack`, `ZoneRules`, `CatchRules`, `BuildingMap`/`BuildingRules`, zone schedule, `AccountRules`, `GroupRules`, `ChatRules`).
- `server/` — Spring Boot. Games stay in memory; accounts, friends, blocks, groups and chat reports are in PostgreSQL (`JdbcClient`, Flyway migrations in `src/main/resources/db/migration`). `api/` thin controllers (`GameController`, `AccountController`, `SocialController`) + error mapping + bearer auth (game token → `PlayerRef`, account token → `AuthenticatedUser`), `game/` domain (`Game`, with the chat), `GameService` (locking), `GameRegistry` (in memory), `GameJanitor` (deletes old games), `account/` (`AccountService`, repositories), `mail/` (`EmailSender`: smtp / log / recording), `social/` (friends, blocks, groups; `InviteRegistry` in memory), `moderation/` (`ReportRepository`), `ratelimit/` (`RateLimiter`), `db/` (`DataRetention`), `buildings/` (`BuildingLoader`: the zone's building outlines from Overpass at game creation; `hovanki.buildings.source` = overpass / fake / off). `TestPostgres` is in the `testFixtures` source set (used by `:e2e` too).
- `clientCore/` — KMP (jvm, android, iosArm64, iosSimulatorArm64), client logic without UI: Ktor `GameApi`/`HttpGameApi`, `AccountApi`, `SocialApi`, `GameConnection`/`PollingGameConnection`, `LocationOutbox`, `ServerClock`, `GameSessionManager` (saves the session and resumes it after a restart, chat), `AccountManager` (the logged-in account, `AccountCredentials`), `SocialManager` (friends, groups, polled inbox), `session/Chat.kt`, `ClientStorage`, `LocationProvider`/`BackgroundTracker`/`SecureStore` interfaces. No Compose, no platform code; the JVM target is for headless e2e bots.
- `composeApp/` — KMP library (`com.android.kotlin.multiplatform.library`): Compose UI (welcome/login, email verification, main screen with tabs, lobby/game/results, full-screen panels for chat, invites and groups; map: `GameMap`, maplibre-compose + OpenFreeMap tiles), Koin, Ktor engines, platform services (`LocationProvider`, `BackgroundTracker`, `SecureStore` — Android Keystore / iOS Keychain, `ProximityScanner`, `CatchCodeScanner`). The server address is fixed at build time (`BuildConstants`), no address field.
- `androidApp/` — thin Android entry point (`Application`, `MainActivity`).
- `e2e/` — JVM end-to-end tests: `BotPlayer` runs `:clientCore` (Ktor/OkHttp) on a simulated phone (`FakeGps` + `Route`/`GpsNoise`, `DeviceClock`, `FakeNetwork`); `Scenario` DSL; `Observer` reads the server's debug endpoints (Spring profile `e2e` only: games, emailed codes, reports). Bots have accounts (`AccountManager`, `SocialManager`) with unique nicknames per run. Tests start the server in-process on a random port on a fresh `TestPostgres` database; `HOVANKI_E2E_SERVER_URL` targets an external one. Device layer: `e2e/run-devices.sh` builds the server and the debug app and starts emulators/simulators, then `e2e devices` (`src/main/.../devices/`) starts the server jar next to its own PostgreSQL (`LocalPostgres`), registers the host's account (`DeviceAccounts`), and drives the devices with Maestro flows (`e2e/maestro/`) mixed with bots: the host logs in from launch options, the others join as guests. `./gradlew :e2e:devices` (and the `.run/` configurations in Android Studio) does the same on the emulators already running, without bash. See `docs/e2e.md`; step-by-step local runs and troubleshooting in `docs/e2e-local.md`.
- `iosApp/` — Xcode project, SwiftUI shell around `MainViewControllerKt.mainViewController()`; see `iosApp/README.md`. Settings in `Configuration/Config.xcconfig` (fixed bundle id `app.hovanki.ios`), per-machine overrides in git-ignored `Local.xcconfig`.
- `deploy/` — server deployment: one EC2 VM with `compose.yaml` (server + Caddy for TLS; database, secrets and SMTP in `.env`), the database on Amazon RDS for PostgreSQL in the same region (TLS verified with the RDS CA bundle `rds-ca.pem`, backups and point-in-time recovery by RDS), `compose.dev.yaml` (PostgreSQL only, for local development), `aws-user-data.sh`, and `hovanki-update.{sh,service,timer}` that pull the `main` image, which `ci.yml` publishes after every push to `main`, and restart the server when it changed (so every push to `main` ends running games); see `docs/deploy.md`.
- Versions: only in `gradle/libs.versions.toml`. Modules reference each other via typesafe accessors (`projects.shared`).

## Commands

```bash
./gradlew spotlessApply                    # format (ktlint); CI runs spotlessCheck
./gradlew check                            # all tests + checks available on this OS, without the e2e scenarios
./gradlew :shared:jvmTest :clientCore:jvmTest :server:test   # fast feedback loop
./gradlew :e2e:test                        # e2e scenarios: bots play whole games (~3 min); reports in e2e/build/reports/e2e/
./gradlew :e2e:devices                     # app on the emulators already running (Android Studio) + bots; needs Maestro
e2e/run-devices.sh --android 2 --bots 3    # app on 2 emulators + bots (needs KVM + Maestro; --ios 1 on macOS); ~15 min in CI
docker compose -f deploy/compose.dev.yaml up -d   # local PostgreSQL for bootRun (tests bring their own)
./gradlew :server:bootRun                  # server on :8080, health at /actuator/health; emailed codes go to its log
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
- **GDPR:** location data and chat stay in memory and are deleted with the game. Accounts live in PostgreSQL with the retention periods of `docs/adr/0004-accounts-friends-chat.md` (`DataRetention`); personal data in a new table needs a retention period and must go with the account (`ON DELETE CASCADE`). Never log coordinates, tokens, passwords, emailed codes, email addresses or chat text. The device stores only the game session, the account (token + profile) and the guest name, through `ClientStorage`/`SecureStore` (Keystore/Keychain, `docs/adr/0002-session-storage.md`); never store coordinates or chat on the device.
- **Database:** migrations are only ever appended (`V<n>__*.sql`); never edit one that was applied. Times come from the injected `Clock`, not SQL `now()`. Games never touch the database on the hot path (`sync`); write after releasing the game lock. Tests get a fresh database from `TestPostgres` (embedded PostgreSQL 17, or `HOVANKI_TEST_DATABASE_URL` pointing at a running server; the `.claude/` SessionStart hook sets it in cloud sessions).
- **Debug/test hooks never reach production:** the observer endpoints (games, emails, reports) exist only with the Spring profile `e2e`; keep `DebugEndpointAbsentTest` green. Test tags (`TestTags`) and `LaunchOptions` must not change release behavior: launch parameters are read only in debug builds. The Android `preview` build type (tester builds, `preview.yml`) is release plus its own application id and shares the no-op twins in `androidApp/src/release`. iOS dev exceptions (local-network ATS) are added to Debug only by `iosApp/Configuration/debug-info-plist.sh`.
- **Build-time settings** come from Gradle properties via the generated `BuildConstants` in `:composeApp` (`hovanki.serverUrl`: the one server of non-debug builds, required, https only). Don't hardcode server addresses; non-debug builds talk HTTPS only. Debug builds use the development machine or `LaunchOptions.server`.
- **Run the long tests yourself before a PR** (CI on push doesn't): changed rules, protocol or client–server behavior → `./gradlew :e2e:test` (~3 min, works in a cloud container without KVM); changed UI or platform code (`:composeApp`, `androidApp`, `iosApp`, Maestro flows) → `./gradlew :e2e:devices` on local emulators if you have them, or trigger `nightly.yml` manually on your branch (`suite=devices`, only the scenario you need). Say in the PR what you ran. See `docs/ci-cd.md`.
- **Tests next to the code:** `shared/src/commonTest` and `clientCore/src/commonTest` (run on JVM and iOS), `shared/src/jvmTest` for JDK cross-checks, `server/src/test` (`GameTest` for rules with an explicit `now`, `GameApiTest` for HTTP round trips with MockMvc). New rule → unit test in `GameTest` or `commonTest`, and an e2e scenario in `e2e/src/test` when it spans client and server. A bug found by an e2e scenario is fixed in the game or server with a test, never worked around in the scenario.
- **Style:** ktlint `intellij_idea`, max line 120, trailing commas (see `.editorconfig`). Run `./gradlew spotlessApply` before committing.
- **Gradle:** configuration cache is on — no configuration-time side effects; no hardcoded versions in build scripts.

## AGP 9 / KMP structure

- Never apply `org.jetbrains.kotlin.android` (kotlin-android) in `androidApp`: AGP 9 has built-in Kotlin.
- Never add `androidTarget()` in KMP modules: they use `com.android.kotlin.multiplatform.library` and the `kotlin { android { ... } }` block.
- Android/shared bytecode is JVM 17, the server toolchain is JDK 21.

## When you change things

- New endpoint: `ApiRoutes` + DTO in `:shared` → `Game`/`GameService` (or an account/social service with an `AuthenticatedUser` parameter) + controller in `:server` → `GameApi`/`AccountApi`/`SocialApi` + `GameSessionManager`/`AccountManager`/`SocialManager` in `:clientCore` → UI in `:composeApp` → API table in `docs/architecture.md`. New errors: an `ErrorReason` on the existing `ErrorCode` (`ApiError.reason`), never a new `ErrorCode`.
- Behavior, commands or setup changed → update `README.md` / `docs/`. Big decisions → new ADR in `docs/adr/NNNN-*.md`.
- Recipes for endpoints, platform services and screens: `docs/architecture.md`, section «Как добавить…».
