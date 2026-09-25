# CLAUDE.md

Hovanki: street hide-and-seek for Android + iOS. Kotlin Multiplatform + Compose Multiplatform client, Spring Boot server, shared Kotlin module for protocol and rules. Docs are in Russian (`docs/`), code and comments in English.

Read first: `docs/architecture.md` (how it works), `docs/adr/0001-stack.md` (why; game rules), `docs/roadmap.md` (what is missing).

## Layout

- `shared/` — KMP (jvm, android, iosArm64, iosSimulatorArm64). `protocol/` DTOs + `ApiRoutes` + `protocolJson`, `totp/` catch codes (pure-Kotlin SHA-1/HMAC), `geo/`, `rules/` (`LocationTrack`, `ZoneRules`, `CatchRules`, zone schedule).
- `server/` — Spring Boot. `api/` thin controller + error mapping + bearer auth, `game/` domain (`Game`), `GameService` (locking), `GameRegistry` (in memory), `GameJanitor` (deletes old games).
- `composeApp/` — KMP library (`com.android.kotlin.multiplatform.library`): Compose UI, Koin, Ktor `GameApi`, `GameConnection`, `ServerClock`, platform services (`LocationProvider`, `BackgroundTracker`, `ProximityScanner`, `CatchCodeScanner`).
- `androidApp/` — thin Android entry point (`Application`, `MainActivity`).
- `iosApp/` — Xcode project, SwiftUI shell around `MainViewControllerKt.mainViewController()`; see `iosApp/README.md`.
- Versions: only in `gradle/libs.versions.toml`. Modules reference each other via typesafe accessors (`projects.shared`).

## Commands

```bash
./gradlew spotlessApply                    # format (ktlint); CI runs spotlessCheck
./gradlew check                            # all tests + checks available on this OS
./gradlew :shared:jvmTest :server:test     # fast feedback loop
./gradlew :server:bootRun                  # server on :8080, health at /actuator/health
./gradlew :androidApp:installDebug         # Android debug build
./gradlew :shared:iosSimulatorArm64Test    # macOS only
```

iOS (framework, app, simulator tests) builds only on macOS with Xcode 26.4+; on Linux iOS targets are skipped (`kotlin.native.ignoreDisabledTargets=true`). Don't try to fix iOS-only failures blind — say so.

## Conventions

- **Shared rules live in `:shared`** and are used by both client and server: thresholds (`GameRules`), zone math (`ZoneSchedule.stateAt`), GPS filtering, TOTP. Never re-implement them on one side.
- **Server is authoritative.** It decides phases, catches and visibility; `Game.snapshotFor(viewer)` never includes positions the viewer may not see. The client only renders and hints.
- **Protocol changes must stay backward compatible**: add fields with default values; never rename or remove fields, enum values or routes. A new enum value breaks old clients when the property has no default (e.g. `VisibleLocation.reason`) — add a new defaulted field instead, or a new API version.
- **All timestamps are server time** (epoch millis). The client converts via `ServerClock`; never use the device clock for protocol data, deadlines or TOTP.
- **`Game` is a pure domain object**: no Spring, no threads, time passed in as `nowMillis`. Time-based transitions go into `advance(now)`; no background tickers. Access goes through `GameService.update(...)` (lock + advance + snapshot).
- **No platform code in `commonMain`.** Platform services are interfaces in `commonMain`, implemented in `androidMain`/`iosMain`, bound via Koin. `expect`/`actual` only for small glue.
- **GDPR:** location data stays in memory and is deleted with the game; never log coordinates or tokens.
- **Tests next to the code:** `shared/src/commonTest` (runs on JVM and iOS), `shared/src/jvmTest` for JDK cross-checks, `server/src/test` (`GameTest` for rules with an explicit `now`, `GameApiTest` for HTTP round trips with MockMvc). New rule → unit test in `GameTest` or `commonTest`.
- **Style:** ktlint `intellij_idea`, max line 120, trailing commas (see `.editorconfig`). Run `./gradlew spotlessApply` before committing.
- **Gradle:** configuration cache is on — no configuration-time side effects; no hardcoded versions in build scripts.

## AGP 9 / KMP structure

- Never apply `org.jetbrains.kotlin.android` (kotlin-android) in `androidApp`: AGP 9 has built-in Kotlin.
- Never add `androidTarget()` in KMP modules: they use `com.android.kotlin.multiplatform.library` and the `kotlin { android { ... } }` block.
- Android/shared bytecode is JVM 17, the server toolchain is JDK 21.

## When you change things

- New endpoint: `ApiRoutes` + DTO in `:shared` → `Game`/`GameService` + controller in `:server` → `GameApi` in `:composeApp` → API table in `docs/architecture.md`.
- Behavior, commands or setup changed → update `README.md` / `docs/`. Big decisions → new ADR in `docs/adr/NNNN-*.md`.
- Recipes for endpoints, platform services and screens: `docs/architecture.md`, section «Как добавить…».
