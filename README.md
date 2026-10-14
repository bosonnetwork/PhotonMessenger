# Photon

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android%2013%2B-green.svg)](https://developer.android.com/)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.1-purple.svg)](https://kotlinlang.org/)
[![Compose](https://img.shields.io/badge/UI-Jetpack%20Compose-blue.svg)](https://developer.android.com/jetpack/compose)

**Photon** is an end-to-end encrypted, federated messenger for Android, built on the
[Boson Network](https://bosonnetwork.io) stack.

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
- [Related Projects](#related-projects)
- [Contributing](#contributing)
- [License](#license)

---

## Overview

Photon is a messaging app with no company in the middle. There is no Photon account, no phone
number, and no central address book. Your identity **is** an Ed25519 key pair made on your phone;
its public key is your globally unique user id, and the private key stays with you.

Photon is **federated**, in much the same sense that email is federated. Anyone can run a Boson
**super node**; you pick one as your home node and connect to it, as you would pick a mail
provider. Messages flow between users on *different* super nodes, and your identity is not owned by
the node you happen to use - it is discovered through the Boson DHT, not through DNS or a provider
database. Changing home nodes does not change who you are, and it does not cost you your history:
the device holds the authoritative copy of your data.

What a super node does is route ciphertext and hold it briefly for offline delivery. What it does
**not** do is read it. See [Security](#security).

**Project status:** version 0.8.0, pre-1.0 and in active development. Runs on **Android 13 (API 33)
and later**. APIs, wire formats, and storage schemas may still change between releases. See
[CHANGELOG.md](CHANGELOG.md) for what each release contains.

---

## Photon in the Boson Ecosystem

Boson Network is a two-layer peer-to-peer platform: a Kademlia DHT (layer 1) carrying identity,
discovery, and signalling, and application services (layer 2) hosted on super nodes. Photon is a
**client** of the super node's services and of its Director's REST API.

```
  +---------------------------------------------------------------+
  |                        Photon (Android)                       |
  |      Jetpack Compose UI  .  ViewModels  .  Repositories       |
  +---------------------------------------------------------------+
       |                |                    |              |
  Director REST   Messaging client       Ion Store      Web Gateway
  (HTTPS/JSON)    (MQTTS, E2E encrypted) (HTTPS,        (HTTPS, public
       |                |                encrypted       profile card)
       |                |                blobs)             |
       v                v                    v              v
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
  account registration, device registration and approval, service discovery, the profile and avatar,
  and the passphrase. It issues a CWT (a CBOR-encoded, Ed25519-signed bearer token) for the
  authenticated routes.
- **Messaging service** - an MQTTS broker that relays already-encrypted payloads between clients
  and queues them for offline devices.
- **Ion Store** - a content-addressed object store, used for attachments too large to inline in a
  message.
- **Web Gateway** - the DHT on the phone's behalf: Photon publishes your public profile card through
  it, and it keeps announcing the card while your phone is off.

Photon does **not** run an embedded DHT node on the phone - that would be costly on battery and
awkward behind mobile NAT. It resolves the service coordinates from the Director's
`GET /api/v1/client/node` endpoint and then talks to those services directly.

**Boson Identity.** [Boson Identity](https://github.com/bosonnetwork/BosonIdentity) is the companion
app that holds your Boson identity on your phone. With it installed, Photon can join your identity
in one step: Boson Identity tells Photon your home node and approves the phone as a new device,
with nothing to scan or type. It is optional; Photon works on its own too.

---

## Features

### Messaging

- **1-to-1 direct messages**, end-to-end encrypted.
- **Group channels** with roles and moderation - create, join, transfer ownership, promote and
  demote members, kick and ban, and rotate the channel session key.
- **Channel invitations** in two forms: a named invite delivered as an in-chat card with
  Join / Ignore actions, and a shareable bearer link.
- **Friend requests and contacts** - send a request, then accept, ignore, resend, or remove it;
  every request shows its direction and state. Senders can be blocked, contacts remarked and
  removed.
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

- **Identity first.** A new identity is made on the phone, and you back up its key (as a file, a
  QR code or text) and check the backup before the account is created. Creating it on the node
  passes a proof-of-work check - no third party involved.
- **Bring in an existing identity** in one of three ways:
  - with **Boson Identity on the same phone**, which gives Photon your home node and approves the
    phone, with nothing to scan;
  - by **approving the phone from another device**: Photon shows a pairing code, which Boson
    Identity or Photon on another device scans and approves;
  - by **importing your key backup** (file, QR code or text).
- **Devices and sessions** - see your devices and live sessions, approve a new device by its
  pairing code, revoke a session, and remove a device.
- **Multiple accounts on one phone** - each Boson identity gets its own isolated profile (separate
  database, separate keys), shown with its avatar and home node and switchable from Settings.
  Signing out ends the session without destroying local data.
- **Profile** - name, bio and photo, kept on the phone and synced with your home node, so a change
  made elsewhere is never silently overwritten.
- **Public profile card** - optionally publish your name, bio, photo and home node, so that anyone
  who knows your ID can look them up.
- **Passphrase** - an optional passphrase guards sensitive account changes, such as adding or
  removing a device. Recovery codes reset a forgotten one, as can the node's web portal or Boson
  Identity, with a linked account or a passkey.

### Application

- Material 3 UI in the Boson brand colors, with light and dark themes; **English and Simplified
  Chinese**.
- A foreground service keeping the messaging connection alive, with local notifications for
  messages and friend requests (previews can be suppressed), and guidance for exempting Photon from
  battery optimization.
- Connection-state banner that distinguishes a recoverable reconnect from a terminal failure -
  an exceeded session quota, say - and offers the right next step.
- Scanners that recognize every Boson code: a super node, a contact's ID, a device's pairing code,
  or a key backup - and that point to Boson Identity for the codes that belong there (a web
  sign-in, an administrator enrollment, moving to a new phone).

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

- The **user private key never reaches a server.** Registration binds only the *public* key to
  the account. When a new device asks for the key, it is sealed to the pairing code that device
  shows before the Director relays it, as opaque bytes.
- The identity's keys are sealed by an AES-256-GCM key in the **Android Keystore** (StrongBox
  where the phone has one), never stored in cleartext. Photon decrypts messages in the background,
  so this key opens without asking you each time; for a key that asks on every use, keep your
  identity in Boson Identity.
- The local message database and all cached media live in app-private storage.
- Keys are libsodium-style 64-byte Ed25519 private keys (seed followed by public key) throughout.

### Transport and server authentication

Transport security is a second layer beneath the end-to-end encryption, not a substitute for it.

- HTTPS to the Director, Ion Store and Web Gateway; MQTTS to the messaging service.
- Services are authenticated by **pinning the Boson node identity**, not by trusting a public CA.
  A super node's TLS certificate carries a binding to its Ed25519 node key, and the client checks
  that binding against the node id you configured - so a self-signed operator certificate is
  verified with no certificate authority in the loop. Directors fronted by a real CA certificate
  are supported too.
- Service-layer authentication uses a CWT signed by your device key. There are no passwords
  anywhere in the system, and so no password database to breach.

### Authentication is by Boson identity

Photon signs in with your Boson keys only: there is no OAuth and no third-party identity provider
in the app. Sign-in accounts you link on your node's web portal (Google, GitHub) are for the portal,
and for resetting a forgotten passphrase.

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
:core:designsystem   Boson brand theme (light/dark), tokens, card sections, shared components
:core:network        Director configuration and app preferences (DataStore)
:core:security       Keystore-sealed identity vault, encrypted secret store, profile isolation
:core:database       Room storage for cold-start rendering and app-owned state
:core:boson-wrapper  Director, MessagingClient, IonStore and Web Gateway clients, coroutine
                     bridges, CBOR codecs, profile cards
:core:qr             QR encoding and camera scanning (CameraX + ML Kit)
:feature:onboarding  Home node, new identity and key backup, pairing, import, Boson Identity
:feature:chat        Conversations list, chat thread, forwarding, image viewer, attachments
:feature:contacts    Contacts, friend requests, channels, invitations, moderation
:feature:settings    Profile and public card, devices, sessions, passphrase, theme, language
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
archives and DEB packages there bundle the Director, the messaging service, Ion Store and the Web
Gateway. The super node's user portal shows its code (a QR code) and address.

### 2. Set up your identity

On first launch, choose **Create a new identity** or **I already have an identity**.

- **New identity** - choose your home node by scanning its code or entering its address. Photon
  makes your key on the phone and has you back it up and check the backup. It then asks for a
  display name, and creates your account after a one-time proof-of-work check (a few seconds; the
  time varies).
- **Existing identity** - if Boson Identity is on the phone, choose **Use Boson Identity on this
  phone** and approve there. Otherwise choose your identity's home node, then either show a pairing
  code to approve from another device, or import your key backup.

To trust a super node with a self-signed certificate, enter its node id under **Advanced options**
(scanning the node's code fills it in).

### 3. Start messaging

Share your Boson ID - Settings shows it as text and as a QR code - add a contact by ID or QR, and
send. Create a channel from Contacts to start a group.

---

## Build from Source

### Prerequisites

| Requirement | Version |
|---|---|
| JDK | 17 (Eclipse Temurin recommended) |
| Android SDK | Platform `android-36`, build-tools 36.x |
| Android Studio | Ladybug or later (optional, but the supported IDE) |
| Gradle | 8.13, through the bundled wrapper - nothing to install |
| Device or emulator | Android 13 (API 33) or later |

The Boson client libraries - `boson-messaging-client`, `boson-ion-store-client`,
`boson-director-client` and `boson-higgs` - come from Maven Central with the other dependencies, so
there is nothing to clone or install by hand.

### Build the app

```bash
git clone https://github.com/bosonnetwork/PhotonMessenger.git
cd PhotonMessenger
./gradlew assembleDebug        # debug APK -> app/build/outputs/apk/debug/
./gradlew installDebug         # build and install on a connected device or emulator
```

The Android SDK location comes from `local.properties` (`sdk.dir=...`) or the `ANDROID_HOME`
environment variable. Android Studio writes `local.properties` for you on first open.

### Release builds

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

- `build-logic` holds a small Gradle plugin that every module applies. Vert.x 5.1 cannot start on
  Android without it: the plugin opens a private field that ART refuses to reach.
- The JVM Boson stack (Vert.x 5, Netty, Jackson) is dexed into the app, so Java 8+ API desugaring
  is on.
- The Gradle build uses the configuration cache and parallel execution. If you hit a
  configuration-cache error after changing build logic, re-run with `--no-configuration-cache` to
  see the underlying failure.
- **Netty's DNS resolver does not work on Android.** Always create the shared `Vertx` instance
  through `BosonClientFactory.newVertx()`, which disables it.
- Boson client wire DTOs consumed by the app must be plain classes, not Java records: Jackson's
  record support fails on Android, and only in release builds.
- The build also resolves from `mavenLocal()`, so an unreleased Boson snapshot can be tested by
  running `./mvnw clean install` in the Boson repository and pointing `boson` in
  `gradle/libs.versions.toml` at that version. **Always include `clean`:** a bare incremental
  `install` can package whatever already sits in `target/classes`, including IDE-compiled classes
  that carry errors surfacing only at runtime inside the app.

---

## Testing

```bash
./gradlew testDebugUnitTest              # JVM unit tests (all modules)
./gradlew lintDebug                      # Android Lint
./gradlew connectedDebugAndroidTest      # instrumented tests (device or emulator required)
```

Unit tests are hermetic and use `mockk` and `turbine`. The instrumented tests come in two kinds:
the `:core` ones (Room storage, Compose components) need only a device or emulator, while the
`:app` `Live*` tests drive the real Boson stack against a running super node - point them at your
own by editing `TestSuperNode` in `app/src/androidTest`.

---

## Related Projects

- [**Boson Identity**](https://github.com/bosonnetwork/BosonIdentity) - the Android app that holds
  your Boson identity, approves your devices and web sign-ins, and administers a super node.
- [**Boson.Core**](https://github.com/bosonnetwork/Boson.Core) - the Kademlia DHT node, Boson
  identity and cryptography, and the shared API every other module builds on.
- [**Boson.Messaging.Client**](https://github.com/bosonnetwork/Boson.Messaging.Client) - the
  federated end-to-end encrypted messaging client library Photon is built on.
- [**Boson.IonStore.Client**](https://github.com/bosonnetwork/Boson.IonStore.Client) - the
  content-addressed object store client library used for attachments.
- [**Boson.Director.Client**](https://github.com/bosonnetwork/Boson.Director.Client) - the client
  library for the super node's REST API, which all Director traffic goes through.
- [**Boson.Releases**](https://github.com/bosonnetwork/Boson.Releases) - pre-built super node
  distributions for macOS, Linux, and Windows.
- [**Boson Network**](https://github.com/bosonnetwork) - all Boson Network repositories, and
  [bosonnetwork.io](https://bosonnetwork.io) for the project site.

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

Please read the [Code of Conduct](CODE_OF_CONDUCT.md) before contributing.

---

## License

Photon is licensed under the [MIT License](LICENSE).

For questions or support, contact the Boson Network team at
[support@bosonnetwork.io](mailto:support@bosonnetwork.io).
