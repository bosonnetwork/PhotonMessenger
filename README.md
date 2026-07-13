# Photon

Decentralized Android messenger built on the Boson Network stack (Messaging Client + Ion Store)
and the Boson Director REST API. Jetpack Compose + Material 3, Clean Architecture + MVVM, Hilt DI,
multi-module Gradle.

See `docs/photon_messenger_design_spec.md` for the design and `docs/impl-plan.md` for the milestone
task tracker.

## Module layout (M0)

```
:app                     Application, MainActivity, navigation, DI wiring
:core:model              Pure-Kotlin domain entities + unified AppError model
:core:designsystem       Material 3 theme (light/dark/dynamic), tokens, components
:core:network            Director REST client (Retrofit/OkHttp), DirectorConfig
:core:security           Android Keystore key management (64-byte libsodium keys)
:core:database           Optional Room cache (cold-start render)
:core:boson-wrapper      MessagingClient + IonStore construction + coroutine bridges
:feature:onboarding      OAuth sign-in / registration / pairing
:feature:chat            Conversations list + chat thread
:feature:contacts        Friends / requests / channels
:feature:settings        Profile, devices/sessions, theme
```

## Prerequisites

- JDK 17
- Android SDK with platform `android-36` and build-tools 36.x
- The Boson client artifacts installed to the local Maven repo (`~/.m2`):
  `io.bosonnetwork:boson-messaging-client:3.0.1` and `io.bosonnetwork:boson-ion-store-client:3.0.1`
  (built from the Boson repo via `mvn install`). The build resolves them via `mavenLocal()`.

## Build

```
./gradlew assembleDebug      # build the debug APK
./gradlew testDebugUnitTest  # run unit tests
./gradlew lintDebug          # Android lint
```

## Status

M0 (project scaffolding) in progress. See `docs/impl-plan.md` for per-task status and
`docs/m0-build-report.md` for the integration risk notes on running the JVM Boson stack on Android.
