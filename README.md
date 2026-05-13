# expo-wearable-messaging

[![npm version](https://img.shields.io/npm/v/expo-wearable-messaging.svg)](https://www.npmjs.com/package/expo-wearable-messaging)
[![npm downloads](https://img.shields.io/npm/dm/expo-wearable-messaging.svg)](https://www.npmjs.com/package/expo-wearable-messaging)

Published on [npm](https://www.npmjs.com/package/expo-wearable-messaging) as `expo-wearable-messaging`.

Expo native module for **phone ↔ companion watch** messaging:

- **Android (phone):** Google Play Services **Wearable Data Layer** — Message API for send/receive, plus optional **Data Layer** items for mirrored JSON (see defaults below).
- **iOS (phone):** **WatchConnectivity** — interactive `sendMessage` when reachable, otherwise `updateApplicationContext` (watch receives via `didReceiveApplicationContext`).

Payloads are **always a single JSON object** (UTF-8 string on Android wire format; `[String: Any]` JSON-serializable dictionary on Apple platforms). **No built-in app domain** — you define keys and types.

---

## Is this “generic”?

**Yes, in the sense that** the module does not interpret your JSON (no fixed schema for sports, health, etc.).

**No, in the sense that** Android ships **fixed defaults** you must mirror in your Wear OS app unless you fork the module:

| Item | Default | Where |
|------|---------|--------|
| Message API path | `/expo-wear-msg` | `ExpoWearableMessagingModule.DEFAULT_MESSAGE_PATH` (Kotlin), `ANDROID_DEFAULT_MESSAGE_PATH` (TypeScript) |
| Phone capability | `phone` | Kotlin `LOCAL_CAPABILITY_PHONE` |
| Watch capability | `watch` | Kotlin `TARGET_CAPABILITY_WATCH` |
| Data Layer URI path filter | path **contains** `expo-wear-report` | `WearMessagingListenerService.DEFAULT_DATA_REPORT_PATH_SEGMENT`, `ANDROID_DEFAULT_DATA_REPORT_PATH_SEGMENT` (TS) |
| Data Layer JSON field | `json` (string) in `DataMap` | `WearMessagingListenerService` |

iOS has **no path** concept: the watch receives the same dictionary the phone sent (plus internal `_wcSeq` on application-context sends from this module for deduplication).

---

## Install (Expo)

1. Add the dependency from **npm**:

   ```bash
   npx expo install expo-wearable-messaging
   ```

   Or with npm / yarn / pnpm:

   ```bash
   npm install expo-wearable-messaging
   ```

   Package page: [npmjs.com/package/expo-wearable-messaging](https://www.npmjs.com/package/expo-wearable-messaging).

2. Run `npx expo prebuild` (or EAS Build) so **iOS Pods** and **Android Gradle** pick up the module.

3. **Android + Wear:** the phone app and the Wear APK must be signed with the **same certificate** (typically the same debug keystore in dev), or the Data Layer / Message API will not pair.

4. **iOS + watchOS:** add a **watchOS target** in Xcode (or `@bacons/apple-targets`), enable the Watch app and **WatchKit / WCSession** usage as you normally would for a paired watch.

---

## JavaScript API (React Native)

Import from `expo-wearable-messaging`:

| API | Role |
|-----|------|
| `sendJsonMessage(payload)` | Phone → watch: `Record<string, unknown>`. On **iOS**, may resolve with a **reply** dictionary if the watch used the reply-capable path. On **Android**, currently resolves with `{}` (no reply wired through). |
| `addMessageListener(cb)` | Watch → phone: parsed JSON object. |
| `addReachabilityListener` / `getReachable` / `refreshReachability` | Android: watch **reachable** via merged nodes + capability. iOS: WatchConnectivity reachability / session snapshot. |
| `addPairedListener` / `addCompanionInstalledListener` / `getPaired` / `getCompanionInstalled` | **iOS only** (meaningful values); on Android the “paired / installed” async methods are stubs (`false`) so the same JS can run on both platforms. |

Constants (Android wire alignment):

- `ANDROID_DEFAULT_MESSAGE_PATH` → `/expo-wear-msg`
- `ANDROID_DEFAULT_DATA_REPORT_PATH_SEGMENT` → `expo-wear-report`

---

## Wear OS companion (Kotlin)

Your Wear app and the phone module must agree on **message path** and **capabilities**.

### 1. Capabilities

The phone registers local capability **`phone`** and listens for capability **`watch`**. Your Wear app should:

- Advertise **`watch`** (or whatever you change both sides to if you fork the module).
- Look up the phone via capability **`phone`** when sending to the phone.

### 2. Phone → watch (Message API)

The phone sends UTF-8 bytes of a JSON string on path **`/expo-wear-msg`** (default).

On the **watch**, handle incoming messages on that **same path**:

```kotlin
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import org.json.JSONObject
import java.nio.charset.StandardCharsets

class MyWearListenerService : WearableListenerService() {

  override fun onMessageReceived(event: MessageEvent) {
    if (event.path != "/expo-wear-msg") return
    val json = String(event.data ?: return, StandardCharsets.UTF_8)
    val obj = JSONObject(json)
    // read your keys, e.g. obj.optString("hello")
  }
}
```

Register the service in the Wear app `AndroidManifest.xml` with `com.google.android.gms.wearable.BIND_LISTENER` (same pattern as the phone module’s listener).

### 3. Watch → phone (Message API)

Send UTF-8 JSON bytes to the phone on **`/expo-wear-msg`**:

```kotlin
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import java.nio.charset.StandardCharsets

fun sendJsonToPhone(application: android.app.Application, json: String) {
  val capabilityClient = Wearable.getCapabilityClient(application)
  capabilityClient.getCapability("phone", CapabilityClient.FILTER_ALL)
    .addOnSuccessListener { info ->
      val node = info.nodes.firstOrNull { it.isNearby } ?: info.nodes.firstOrNull()
        ?: return@addOnSuccessListener
      Wearable.getMessageClient(application)
        .sendMessage(node.id, "/expo-wear-msg", json.toByteArray(StandardCharsets.UTF_8))
    }
}
```

The phone’s `WearMessagingListenerService` and in-process `MessageClient` listener forward that payload to JS as `onMessage`.

### 4. Optional: Data Layer mirror (watch → phone)

If Message delivery is flaky, you can **mirror** the same JSON string in a `DataMap` under a path whose URI **contains** `expo-wear-report`, with key **`json`**:

```kotlin
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable

fun mirrorJsonToPhone(context: android.content.Context, json: String) {
  val request = PutDataMapRequest.create("/expo-wear-report").apply {
    dataMap.putString("json", json)
    dataMap.putLong("_seq", System.currentTimeMillis())
  }.asPutDataRequest()
  Wearable.getDataClient(context).putDataItem(request)
}
```

The bundled `WearMessagingListenerService` on the phone listens for path segments matching **`expo-wear-report`** and emits the `json` field through the same JS pipeline as Message API (with short deduplication if both fire).

---

## Apple Watch (Swift / watchOS)

The **iPhone** module uses `WCSession`: it sends a **JSON object** as a dictionary (`[String: Any]`). Your watch extension should activate the default session and implement `WCSessionDelegate`.

### Receive from phone (interactive)

```swift
import WatchConnectivity

final class SessionDelegate: NSObject, WCSessionDelegate {
  func session(
    _ session: WCSession,
    didReceiveMessage message: [String: Any]
  ) {
    // Same keys as your JS payload; system types only (String, NSNumber, Array, Dictionary).
    if let title = message["title"] as? String {
      // ...
    }
  }

  func session(
    _ session: WCSession,
    didReceiveMessage message: [String: Any],
    replyHandler: @escaping ([String: Any]) -> Void
  ) {
    // Phone used sendMessage with reply; you can replyHandler(["ok": true])
    replyHandler([:])
  }

  func session(_ session: WCSession, didReceiveApplicationContext applicationContext: [String: Any]) {
    // Used when phone falls back to updateApplicationContext (not reachable / simulator).
    // May include "_wcSeq" added by the phone module — ignore or strip for your domain.
  }

  func session(
    _ session: WCSession,
    activationDidCompleteWith activationState: WCSessionActivationState,
    error: Error?
  ) { }

  #if os(watchOS)
  // watchOS: no sessionDidBecomeInactive / sessionDidDeactivate
  #endif
}
```

Activate on the watch (e.g. in `ExtensionDelegate` or SwiftUI `@main`):

```swift
if WCSession.isSupported() {
  let session = WCSession.default
  session.delegate = SessionDelegate()
  session.activate()
}
```

### Send watch → phone

Use `WCSession.default.sendMessage(_:replyHandler:errorHandler:)` with a **JSON-serializable** dictionary (string keys, property-list compatible values). The phone module forwards that to JS as `onMessage` (serialized to JSON string for the event).

---

## Changing Android defaults

If you need a different message path or Data Layer convention:

1. Change **`DEFAULT_MESSAGE_PATH`** in `ExpoWearableMessagingModule.kt` and mirror the same string in your Wear app.
2. Change **`DEFAULT_DATA_REPORT_PATH_SEGMENT`** / `DATA_JSON_KEY` in `WearMessagingListenerService.kt` (and your Wear `PutDataMapRequest` paths/keys) if you use Data Layer mirroring.
3. Optionally re-export matching constants from `src/index.ts` for documentation parity.

---

## Files in this package (orientation)

| Area | File |
|------|------|
| JS entry | `src/index.ts` |
| iOS native | `ios/ExpoWearableMessagingModule.swift` |
| Android module + listener | `android/.../ExpoWearableMessagingModule.kt`, `WearMessagingListenerService.kt` |
| Android manifest (BIND_LISTENER) | `android/src/main/AndroidManifest.xml` |

---

## License

MIT — see [`LICENSE`](./LICENSE).
