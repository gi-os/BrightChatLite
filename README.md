# Chat Lite (BrightChatLite)

An iMessage client for the Light Phone III, built as an official Light SDK tool. It talks to
your own [BlueBubbles Server](https://bluebubbles.app) (a Mac signed into iMessage) over the
server's REST API. It is the Tool Library version of BrightChat, Giovanni's sideloaded
iMessage app, cut down to what a Light tool is allowed to do.

Built on [lightphone/light-sdk](https://github.com/lightphone/light-sdk) (MIT). The SDK modules
(`sdk/`, `plugin/`, `lint-rules/`) are untouched upstream code; the tool lives in `tool/`.

## What it does

- **Setup**: type the server address and password, or scan a QR code with the SDK's
  `LightQrCodeScanner`. Accepted codes: the BlueBubbles Server's own client QR (a JSON array,
  password first, URL second), a JSON object with `url` and `password`, or a URL with
  `?password=`. The address and password are checked against `server/info` before they are
  saved. The password is stored encrypted with an Android Keystore key.
- **Conversation list**, newest first, built the same way BrightChat builds it: one
  `message/query` sweep, so a freshly active chat can't fall off the list. Forked group rooms
  collapse into one row. Unread chats get a dot. Favorites (star a thread) are pinned to the top.
  Chats from people in the Mac's address book are on the main list; unknown senders are one tap
  away behind UNKNOWN, as in BrightChat.
- **Names** come from the Mac's address book through `GET /api/v1/contact`, normalized so
  `+1 (315) 212-2695` and `+13152122695` match.
- **Thread view**: text, sender names on the first message of a run, day dividers, group
  events (renames, member changes), tapbacks shown under the message they react to, and
  "Delivered" / "Read 3:14 PM" / "Not delivered" under your newest message in a 1:1.
- **Photos** are downloaded from `attachment/:guid/download`, downsampled with
  `BitmapFactory` and shown inline (cached on disk, 60 MB cap). Other attachments (video,
  audio, contact cards, PDFs) show as a labeled placeholder such as `[Video · IMG_0042.MOV]`.
- **Reply and new message**: REPLY opens the LP3 keyboard; the message shows immediately and is
  reconciled with the server's copy. The compose icon on the list starts a 1:1 with a phone
  number or email.
- **Mark read**: opening a thread marks it read on the Mac when the server's Private API is on
  (the same gate BrightChat uses), so the unread state clears on your other Apple devices.
- **Live updates by polling**: the open thread polls every 5 seconds and the list every 10
  seconds, only while that screen is showing and only when `LightConnectivity` reports a
  network. A `LightWork` job refreshes the cache every 15 minutes in the background.
- **Offline**: conversations and the newest 5,000 messages are cached in Room, so the tool opens
  to your chats without a connection.
- The scroll wheel scrolls the list and the thread.

## What it drops, and why

| BrightChat feature | Why it is not here |
| --- | --- |
| Matrix / Beeper bridges (WhatsApp, Signal, ...) | Needs trixnity, which is not on the SDK's dependency allowlist. Dropped entirely. |
| Socket.IO live delivery | The socket.io client is not allowlisted. Polling replaces it while a screen is open. ktor websockets are allowed, so a raw Engine.IO client could replace polling later. |
| System notifications, heads-up box, buzz on new message | Tools have no notifications API (`NotificationManager` needs `getSystemService`, which the SDK blocks), and overlay windows and activities are blocked. |
| Typing indicators, reactions, replies, edits, group management | Kept the scope to reading and sending text. The endpoints exist; they can come back. |
| Sending photos, the in-app photo picker, inline camera | The only camera access a tool gets is QR scanning, there is no `contentResolver` for the gallery, and CameraX is not allowlisted. |
| Video and audio playback | Not attempted in this version; those attachments show as labeled placeholders. |
| Color while viewing a photo | Needs `WRITE_SECURE_SETTINGS` and a settings write; not available to tools. |
| The phone's own contacts and contact photos | `contentResolver` is blocked. Names come from the Mac's address book instead. |
| Share target, notification deep links, dialer, newsletter, GIFs, AI agents | Need intents, other apps, or services a tool cannot reach. |
| Catch-up alarms, boot receivers, network callbacks | Tools get `LightWork` (15-minute minimum) and nothing else for background work. |

## Setup

1. Run BlueBubbles Server on a Mac signed into iMessage. Note its address and password.
2. Make the server reachable from the phone: the Tailscale app on both (use the Mac's
   `100.x.y.z` address or MagicDNS name) or the same Wi-Fi. Plain `http://` works; the tool
   declares the `cleartext-http` capability for this reason.
3. Open Chat Lite, tap SET UP, and either type the address (for example
   `http://100.64.0.2:1234`) and password, or tap SCAN QR and scan the code BlueBubbles Server
   shows under "Connect a client".
4. Optional: turn on BlueBubbles' Private API on the Mac so reading here marks chats read
   everywhere and group sends are more reliable.

## Endpoints used

All are BlueBubbles REST v1 calls BrightChat already makes, authenticated with `?password=`:
`GET server/info`, `POST message/query`, `GET chat/:guid/message`, `POST message/text`,
`POST chat/:guid/read`, `POST chat/new`, `GET contact`, `GET attachment/:guid/download`.

## Permissions

`INTERNET`, `ACCESS_NETWORK_STATE` (skip polling when offline) and `CAMERA` (QR setup only).
Capability: `cleartext-http`.

## Build

```
./gradlew -DlightSdk.toolOnly=true :tool:assembleDebug
```

Unit tests and Light's lint rules:

```
./gradlew -DlightSdk.toolOnly=true :tool:testDebugUnitTest :tool:lintDebug
```

GitHub Actions runs all three on every push (`.github/workflows/build-tool.yml`).

## Tool Library submission notes

- Tool id `com.gios.brightchatlite`, label "Chat Lite". Bump `versionCode` in
  `tool/lighttool.toml` for every release.
- Uses only allowlisted libraries already provided by `:sdk:client` (ktor + OkHttp,
  kotlinx-serialization, Room, DataStore, LightWork). No extra dependencies.
- The tool needs a server the user runs themselves. Reviewers will need a BlueBubbles Server
  (or should be told the tool shows its setup screen until one is configured).
- No data leaves the phone except to the server the user enters.

## License

MIT, see `LICENSE`.
