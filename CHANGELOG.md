# Changelog

All notable changes to Photon are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and Photon is pre-1.0: until 1.0, minor
versions may change APIs, wire formats, and local storage schemas.

## 0.5.1 - 2026-09-28

First public release. Built on the Boson Network stack 3.1.2.

### Messaging

- End-to-end encrypted 1-to-1 direct messages.
- Group channels with roles and moderation: create, join, transfer ownership, promote and demote
  members, kick, ban, and rotate the channel session key.
- Channel invitations as a named in-chat card with Join / Ignore actions, or as a shareable bearer
  link.
- Friend requests with direction and state: send, accept, ignore, resend, remove, or block the
  sender. Contacts can be remarked, removed, and blocked.
- Message actions: copy, forward to a contact or channel, save, delete, and retry a failed send.
- Local history in an embedded database, so the app opens instantly and received messages stay
  readable offline.

### Attachments

- Photos, files, and voice messages. Small payloads travel inline inside the encrypted message;
  larger ones go to Ion Store and are referenced by content id.
- Voice messages recorded as Opus/Ogg with a press-and-hold composer (slide to cancel, swipe to
  lock) and inline playback. Requires Android 10 or later.
- Every Ion Store object is encrypted with a fresh one-time key before it leaves the device; the key
  travels inside the end-to-end encrypted message and never reaches the server.
- Full-screen image viewer and share-out to other apps.

### Identity and devices

- Three ways to get an account, all keeping the private key on the device: permissionless creation
  gated by a proof-of-work challenge, OAuth sign-in (Google, GitHub) where the operator requires it,
  or importing an existing identity key by QR scan or paste.
- Multi-device pairing by QR with approval from an existing device, a live session list, remote
  session revocation, and device deregistration.
- Multiple accounts on one phone, each Boson identity in its own isolated profile. Signing out ends
  the session without destroying local data.
- Profile and avatar hosted by the super node's Director, plus an optional passphrase gating
  sensitive actions such as adding a device or revealing the identity key.

### Application

- Material 3 interface with light, dark, and dynamic color.
- English and Simplified Chinese.
- Foreground service keeping the messaging connection alive, with local notifications for messages
  and friend requests; previews can be suppressed.
- Connection-state banner that distinguishes a recoverable reconnect from a terminal failure, such
  as an exceeded session quota, and offers the next step.
- QR codes for your own id, for device pairing, and for the super node address.

### Security

- Message payloads encrypted with Curve25519 authenticated public-key encryption, keyed from the
  participants' Boson identities; channel messages encrypted to a rotatable channel session key.
- Attachments encrypted separately with a random one-time XChaCha20-Poly1305 key per upload.
- Super nodes are authenticated by pinning their Boson node identity, so a self-signed operator
  certificate is verified with no certificate authority involved. Directors behind a public CA
  certificate work too.
- No passwords anywhere: service-layer authentication uses a token signed by the device key.
- No presence, no typing indicators, and no read receipts, by design - all three leak behavioural
  metadata to anyone observing the relay.

### Known issues

- Voice recording and saving media to the gallery need Android 10 or later; dynamic color needs
  Android 12 or later.
