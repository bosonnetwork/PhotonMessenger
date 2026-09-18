# Photon

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android%2013%2B-green.svg)](https://developer.android.com/)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.1-purple.svg)](https://kotlinlang.org/)
[![Compose](https://img.shields.io/badge/UI-Jetpack%20Compose-blue.svg)](https://developer.android.com/jetpack/compose)

**Photon** is an end-to-end encrypted, federated messenger for Android, built on the
[Boson Network](https://github.com/bosonnetwork) stack.

---

## Table of Contents

- [Overview](#overview)
- [Photon in the Boson Ecosystem](#photon-in-the-boson-ecosystem)
- [Features](#features)
- [Security](#security)
- [Architecture](#architecture)
- [Getting Started](#getting-started)
- [Build from Source](#build-from-source)
- [Testing](#testing)
- [Documentation](#documentation)
- [Related Projects](#related-projects)
- [Contributing](#contributing)
- [License](#license)

---

## Overview

Photon is a messaging app with no company in the middle. There is no Photon account, no phone
number, and no central address book. Your identity **is** an Ed25519 key pair generated on your
device; its public key is your globally unique user id, and the private key never leaves the phone.

Photon is **federated**, in much the same sense that email is federated. Anyone can run a Boson
**super node**; you pick one as your home node and connect to it, as you would pick a mail
provider. Messages flow between users on *different* super nodes, and your identity is not owned by
the node you happen to use - it is discovered through the Boson DHT, not through DNS or a provider
database. Changing home nodes does not change who you are, and it does not cost you your history:
the device holds the authoritative copy of your data.

What a super node does is route ciphertext and hold it briefly for offline delivery. What it does
**not** do is read it. See [Security](#security).

**Project status:** version 0.5.1, feature-complete for the core messaging experience and in active
development. APIs, wire formats, and storage schemas may still change between releases.

---

## Photon in the Boson Ecosystem

Boson Network is a two-layer peer-to-peer platform: a Kademlia DHT (layer 1) carrying identity,
discovery, and signalling, and application services (layer 2) hosted on super nodes. Photon is a
**client** of two of those layer-2 services, plus the Director's REST API.

```
  +---------------------------------------------------------------+
  |                        Photon (Android)                       |
  |      Jetpack Compose UI  .  ViewModels  .  Repositories       |
  +---------------------------------------------------------------+
        |                    |                          |
   Director REST      Messaging client              Ion Store
   (HTTPS/JSON)       (MQTTS, E2E encrypted)        (HTTPS, encrypted blobs)
        |                    |                          |
        v                    v                          v
  +---------------------------------------------------------------+
  |                    Boson Super Node (layer 2)                 |
  |  Director  .  Messaging service  .  Ion Store  .  WebGateway  |
  +---------------------------------------------------------------+
                              |
                              v
  +---------------------------------------------------------------+
  |             Boson DHT (layer 1, KadNode over UDP)             |
  |       Ed25519 peer identity  .  peer + value discovery        |
  +---------------------------------------------------------------+
```

- **Director** - the super node's supervisor and REST API (`/api/v1/...`). Photon uses it for
  account registration, identity binding, service discovery, profile and avatar storage, and the
  device registry. It issues a CWT (a CBOR-encoded, Ed25519-signed bearer token) for the
  authenticated routes.
- **Messaging service** - an MQTTS broker that relays already-encrypted payloads between clients
  and queues them for offline devices.
- **Ion Store** - a content-addressed object store, used for attachments too large to inline in a
  message.

Photon does **not** run an embedded DHT node on the phone - that would be costly on battery and
awkward behind mobile NAT. It resolves the messaging and Ion Store coordinates from the Director's
`GET /api/v1/client/node` endpoint and then talks to those services directly. Running a full node
remains possible for self-hosted deployments; it is simply out of scope for the mobile client.

---

## Features

### Messaging

- **1-to-1 direct messages**, end-to-end encrypted.
- **Group channels** with roles and moderation - create, join, transfer ownership, promote and
  demote members, kick and ban, and rotate the channel session key.
- **Channel invitations** in two forms: a named invite delivered as an in-chat card with
  Join / Ignore actions, and a shareable bearer link.
- **Friend requests and contacts** - request, accept, remark, and remove.
- **Message actions** - copy, forward to a contact or channel, save, delete, and retry a failed
  send.
- **Local history** - conversations and messages are stored on the device in an embedded SQLite
  database, so the app opens instantly and everything already received stays readable offline.

### Attachments

- **Photos, files, and voice messages.** Small payloads travel inline inside the encrypted
  message; larger ones are uploaded to Ion Store and referenced by content id.
- **Voice messages** recorded as Opus/Ogg with a press-and-hold composer (slide to cancel, swipe
  to lock) and inline playback.
- **Every Ion Store object is encrypted with a fresh one-time key** before it leaves the device;
  that key travels inside the end-to-end encrypted message, never to the server.
- Full-screen image viewer, and share-out to other apps.

### Identity and devices

- **Three ways to get an account**, all of which keep the private key on the device:
  - **Permissionless creation** gated by a proof-of-work challenge - no third party involved.
  - **OAuth sign-in** (Google, GitHub) where the operator requires it, used for onboarding only
    and never as the ongoing authenticator.
  - **Import an existing identity key**, by QR scan from another device or by pasting it.
- **Multi-device support** - pair a new device by QR, approve it from an existing one, and watch
  the live session list. Devices can be deregistered and sessions revoked remotely.
- **Multiple accounts on one phone** - each Boson identity gets its own isolated profile
  (separate database, separate secrets), switchable from Settings. Signing out ends the session
  without destroying local data.
- **Profile and avatar** hosted by the Director, plus an optional passphrase gating sensitive
  actions such as adding a device or revealing the identity key.

### Application

- Material 3 UI with light, dark, and dynamic color; **English and Simplified Chinese**.
- Foreground service keeping the messaging connection alive, with local notifications for
  messages and friend requests (previews can be suppressed).
- Connection-state banner that distinguishes a recoverable reconnect from a terminal failure -
  an exceeded session quota, say - and offers the right next step.
- QR codes for your own id, for device pairing, and for the super node address.

### Not implemented, by design

No presence, no typing indicators, and no read receipts. All three leak behavioural metadata to
anyone observing the relay, and Photon deliberately does not emit them.

---

## Security

Photon's security model is **client-centric and trustless**: the trust boundary sits at the device
and at the cryptographic protocol, not at the infrastructure. You do not have to trust the super
node you connect to - not for confidentiality, and not for integrity.

### End-to-end encryption

- Every message payload is encrypted on the sending device and decrypted only on the recipient
  devices, using **Curve25519 authenticated public-key encryption** (libsodium `crypto_box`:
  X25519 key agreement with XSalsa20-Poly1305), keyed from the participants' Boson identities.
- **Channel messages** are encrypted to a channel session key held only by members, and that key
  can be rotated - so a removed member cannot read anything sent after their removal.
- **Attachments** are encrypted separately, with a random one-time XChaCha20-Poly1305 key
  (libsodium `secretstream`) generated per upload. The key is carried inside the end-to-end
  encrypted message body, so the store holds only ciphertext and never sees a key.
- Payloads are authenticated, not merely encrypted: a modified ciphertext fails its MAC check and
  is rejected by the receiving client.

### What a super node can and cannot do

A super node is **routing and temporary storage infrastructure**. It is not a trusted party.

| A super node can | A super node cannot |
|---|---|
| Relay opaque ciphertext between clients | Read message text, attachments, or voice notes |
| Queue undelivered payloads for offline devices | Modify or forge content undetectably - tampering fails authentication at the client |
| Store attachment blobs it cannot decrypt | Recover an attachment key; keys never reach the server |
| Observe routing metadata (who connects, envelope addressing, timing, sizes) | Impersonate a user; it holds no user private key |
| Refuse service, or delete queued data | Read or export your private key, or silently register a device against your account |

Metadata is the honest exception. A relay necessarily observes who connects and when, and the
addressing on the envelopes it routes. Photon narrows this where it can - no presence, no read
receipts, encrypted and content-addressed blobs - but it does not claim to hide it.

### Keys and local storage

- The **user private key never leaves the device.** Registration binds only the *public* key to
  the account. Even in the multi-device transfer flow the key is end-to-end encrypted to the
  receiving device before the Director relays it, as opaque bytes.
- Private keys are held in an encrypted store backed by the **Android Keystore** (hardware-backed
  where the device provides it), never in cleartext, and an optional passphrase can gate the
  operations that expose or extend them.
- The local message database and all cached media live in app-private storage.
- Keys are libsodium-style 64-byte Ed25519 private keys (seed followed by public key) throughout.

### Transport and server authentication

Transport security is a second layer beneath the end-to-end encryption, not a substitute for it.

- HTTPS to the Director and Ion Store; MQTTS to the messaging service.
- Services are authenticated by **pinning the Boson node identity**, not by trusting a public CA.
  A super node's TLS certificate carries a binding to its Ed25519 node key, and the client checks
  that binding against the node id you configured - so a self-signed operator certificate is
  verified with no certificate authority in the loop. Directors fronted by a real CA certificate
  are supported too.
- Service-layer authentication uses a CWT signed by your device key. There are no passwords
  anywhere in the system, and so no password database to breach.

### Authentication is by Boson identity

Where an operator enables OAuth, it is **onboarding-only KYC** - a Sybil-resistance and
profile-population step. Once a Boson identity is bound, the session is authenticated solely by
that identity and no OAuth token is retained. An account created through the proof-of-work path
never involves a third-party identity provider at all.

> **Disclaimer.** Photon has not undergone an independent third-party security audit. Please
> evaluate it accordingly before relying on it for high-risk communication, and report suspected
> vulnerabilities privately (see [Contributing](#contributing)).

---

## Architecture

Clean Architecture with MVVM, in a multi-module Gradle build. UI in Jetpack Compose, dependency
injection with Hilt, asynchrony with coroutines bridged onto the Boson libraries'
`CompletableFuture` API.

```
:app                 Application, MainActivity, navigation, DI wiring, foreground service,
                     notifications, session control, account switching
:core:model          Pure-Kotlin domain entities, unified AppError model, display-profile policy
:core:designsystem   Material 3 theme (light/dark/dynamic), tokens, shared components
:core:network        Director configuration and app preferences (DataStore)
:core:security       Android Keystore key management, encrypted secret store, profile isolation
:core:database       Room storage for cold-start rendering and app-owned state
:core:boson-wrapper  Director, MessagingClient and IonStore clients, coroutine bridges, CBOR codecs
:core:qr             QR encoding and camera scanning (CameraX + ML Kit)
:feature:onboarding  Super node selection, registration (PoW / OAuth / import), identity binding
:feature:chat        Conversations list, chat thread, forwarding, image viewer, attachments
:feature:contacts    Contacts, friend requests, channels, invitations, moderation
:feature:settings    Profile, devices, sessions, pairing, language, appearance
:baselineprofile     Startup baseline profile generation (macrobenchmark)
```

**Layering rule:** `feature:*` modules depend on `core:*` modules and never on each other; `:app`
wires them together. Repositories own all Boson library access, so the UI layer never touches a
`MessagingClient` directly.

---

## Getting Started

### 1. Get access to a super node

Photon needs a Boson super node to connect to. Either use one that someone already operates, or
run your own from [Boson.Releases](https://github.com/bosonnetwork/Boson.Releases) - the release
archives and DEB packages there bundle the Director, the messaging service, and Ion Store. The
super node's user portal shows the address and node id you will need.

### 2. Connect the app

On first launch Photon asks for the super node address:

- **Super Node URL** - for example `https://node.example.com:9000`.
- **Server ID** (advanced) - the super node's Boson node id in base58. Required to trust a
  self-signed certificate; leave it blank if the node is fronted by a public CA certificate.

Scanning the QR code from the node's user portal fills both in automatically.

### 3. Create or import your identity

- **Create a new account** - Photon generates your key pair on the device and solves a one-time
  proof-of-work challenge (a few seconds; the exact time varies), then asks for a display name and
  an optional passphrase.
- **Sign in to an existing account** - import your identity key by scanning the QR code from a
  device where you are already signed in, or by pasting the key.

Where the operator has enabled OAuth, signing in with Google or GitHub is offered as an
alternative onboarding route.

### 4. Start messaging

Share your Boson id - Settings shows it as text and as a QR code - add a contact by id or QR, and
send. Create a channel from Contacts to start a group.

---

## Build from Source

### Prerequisites

| Requirement | Version                                            |
|---|----------------------------------------------------|
| JDK | 17 (Eclipse Temurin recommended)                   |
| Android SDK | Platform `android-36`, build-tools 36.x            |
| Android Studio | Ladybug or later (optional, but the supported IDE) |
| Boson client artifacts | `3.1.0` in the local Maven repository (see below)  |
| Boson Director client | `3.1.2-SNAPSHOT` in the local Maven repository (see below) |

Photon supports **Android 13 (API 33) and later**, compiles against API 36, and enables Java 8+
API desugaring because it dexes the JVM Boson stack (Vert.x 5, Netty, Jackson).

### 1. Install the Boson client libraries

Photon resolves `io.bosonnetwork:boson-messaging-client`,
`io.bosonnetwork:boson-ion-store-client` and `io.bosonnetwork:boson-director-client` from
`mavenLocal()`. Build them from their own repositories, in this order - each one installs artifacts
the next depends on:

```bash
# 1. Parent POM and dependency BOM
git clone https://github.com/bosonnetwork/Boson.Parent.git
(cd Boson.Parent && ./mvnw clean install)

git clone https://github.com/bosonnetwork/Boson.Dependencies.git
(cd Boson.Dependencies && ./mvnw clean install)

# 2. Core (DHT, crypto, shared API)
git clone https://github.com/bosonnetwork/Boson.Core.git
(cd Boson.Core && ./mvnw clean install -DskipTests)

# 3. The two client libraries Photon links against
git clone https://github.com/bosonnetwork/Boson.Messaging.Client.git
(cd Boson.Messaging.Client && ./mvnw clean install -DskipTests)

git clone https://github.com/bosonnetwork/Boson.IonStore.Client.git
(cd Boson.IonStore.Client && ./mvnw clean install -DskipTests)

# 4. The Director client, which all Director communication goes through
git clone https://github.com/bosonnetwork/Boson.Director.Client.git
(cd Boson.Director.Client && ./mvnw clean install -DskipTests)
```

The Director client is not released yet: Photon uses its `3.1.2-SNAPSHOT`, which needs the parent
POM, dependency BOM and core at that same snapshot version in the local repository. It also brings
`boson-api` of that version, which Gradle then resolves in place of the `3.1.0` one.

> **Always include `clean`.** A bare incremental `install` can package whatever already sits in
> `target/classes` - including classes built by the IDE's compiler, which may embed errors that
> surface only at runtime inside the app. Use `clean install` when publishing to `~/.m2`.

### 2. Build the app

```bash
git clone https://github.com/bosonnetwork/PhotonMessenger.git
cd PhotonMessenger
./gradlew assembleDebug        # debug APK -> app/build/outputs/apk/debug/
./gradlew installDebug         # build and install on a connected device or emulator
```

The Android SDK location comes from `local.properties` (`sdk.dir=...`) or the `ANDROID_HOME`
environment variable. Android Studio writes `local.properties` for you on first open.

### 3. Release builds

```bash
./gradlew assembleRelease
```

Release builds are minified and resource-shrunk with R8. Signing material is supplied
out-of-band - never committed - through Gradle properties or environment variables:

| Property / env var | Meaning |
|---|---|
| `PHOTON_KEYSTORE_FILE` | Path to the keystore |
| `PHOTON_KEYSTORE_PASSWORD` | Keystore password |
| `PHOTON_KEY_ALIAS` | Signing key alias |
| `PHOTON_KEY_PASSWORD` | Signing key password |

When these are absent, `assembleRelease` still produces an unsigned APK, so the pipeline stays
testable.

### Development environment notes

- The Gradle build uses the configuration cache and parallel execution. If you hit a
  configuration-cache error after changing build logic, re-run with `--no-configuration-cache` to
  see the underlying failure.
- **Netty's DNS resolver does not work on Android.** Always create the shared `Vertx` instance
  through `BosonClientFactory.newVertx()`, which disables it.
- Boson client wire DTOs consumed by the app must be plain classes, not Java records: Jackson's
  record support fails on Android, and only in release builds.

---

## Testing

```bash
./gradlew testDebugUnitTest              # JVM unit tests (all modules)
./gradlew lintDebug                      # Android Lint
./gradlew connectedDebugAndroidTest      # instrumented tests (device or emulator required)
```

Unit tests are hermetic and use `mockk` and `turbine`. The instrumented suite exercises the real
Boson stack against a live super node, so it needs a reachable node and network access.

---

## Documentation

| Document | Contents |
|---|---|
| [`docs/photon_messenger_design_spec.md`](docs/photon_messenger_design_spec.md) | The implementation blueprint: Boson API analysis, auth and identity flows, screen specs, architecture, roadmap |
| [`docs/impl-plan.md`](docs/impl-plan.md) | Milestone task tracker |
| [`docs/security-review.md`](docs/security-review.md) | Security review notes |
| [`docs/persistence-option-a.md`](docs/persistence-option-a.md) | Local persistence design |
| [`docs/m0-build-report.md`](docs/m0-build-report.md) | Integration risk notes on running the JVM Boson stack on Android |
| [`docs/m6-report.md`](docs/m6-report.md) | Release-polish milestone report |

---

## Related Projects

- [**Boson.Core**](https://github.com/bosonnetwork/Boson.Core) - the Kademlia DHT node, Boson
  identity and cryptography, and the shared API every other module builds on.
- [**Boson.Messaging.Client**](https://github.com/bosonnetwork/Boson.Messaging.Client) - the
  federated end-to-end encrypted messaging client library Photon is built on.
- [**Boson.IonStore.Client**](https://github.com/bosonnetwork/Boson.IonStore.Client) - the
  content-addressed object store client library used for attachments.
- [**Boson.Releases**](https://github.com/bosonnetwork/Boson.Releases) - pre-built super node
  distributions for macOS, Linux, and Windows.
- [**Boson Network**](https://github.com/bosonnetwork) - all Boson Network repositories.

---

## Contributing

Contributions are welcome - code, documentation, translations, and bug reports alike.

1. Fork the repository and create a feature branch.
2. Make your change and add tests where applicable.
3. Ensure `./gradlew testDebugUnitTest lintDebug` passes.
4. Open a pull request describing the change and how you verified it.

**Reporting issues.** Open a GitHub issue with the app version (Settings shows it), your Android
version and device, and clear reproduction steps.

**Reporting vulnerabilities.** Please do *not* open a public issue for a security problem. Email
[support@bosonnetwork.io](mailto:support@bosonnetwork.io) with the details and give us a chance to
ship a fix first.

**House conventions.** ASCII-only punctuation in source, comments, logs, and configuration; new
source files carry the project MIT header; user-visible strings live in `res/values` with a
per-module prefix and must be mirrored in `values-zh-rCN`.

Please read the [Code of Conduct](CODE_OF_CONDUCT.md) before contributing.

---

## License

Photon is licensed under the [MIT License](LICENSE).

For questions or support, contact the Boson Network team at
[support@bosonnetwork.io](mailto:support@bosonnetwork.io).
