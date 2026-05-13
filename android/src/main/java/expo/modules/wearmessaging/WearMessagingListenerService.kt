package expo.modules.wearmessaging

import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import java.nio.charset.StandardCharsets

/**
 * Receives Message API payloads and Data Layer items from the watch. Runs in a Play-services–bound
 * process so delivery works even when the Expo module is not yet initialized (payloads are queued in
 * [WearMessagingEventBridge]).
 */
class WearMessagingListenerService : WearableListenerService() {
  override fun onMessageReceived(messageEvent: MessageEvent) {
    val payload = messageEvent.data ?: return
    if (payload.isEmpty()) {
      return
    }
    val json = String(payload, StandardCharsets.UTF_8)
    WearMessagingEventBridge.emitPayload(json)
  }

  override fun onDataChanged(dataEvents: DataEventBuffer) {
    try {
      dataEvents.use { buffer ->
        val count = buffer.count
        for (i in 0 until count) {
          val event = buffer[i]
          if (event.type != DataEvent.TYPE_CHANGED) {
            continue
          }
          val path = event.dataItem.uri.path ?: continue
          if (!path.contains(DEFAULT_DATA_REPORT_PATH_SEGMENT)) {
            continue
          }
          val json =
            try {
              DataMapItem.fromDataItem(event.dataItem).dataMap.getString(DATA_JSON_KEY)
            } catch (_: Exception) {
              null
            } ?: continue
          WearMessagingEventBridge.emitPayload(json)
        }
      }
    } catch (_: Exception) {
      // Ignore malformed buffers.
    }
  }

  companion object {
    /**
     * Matched with [String.contains] against the data item URI path so mirrored JSON (e.g. from
     * [PutDataMapRequest]) reaches JS. Use the same path segment on the watch side.
     */
    const val DEFAULT_DATA_REPORT_PATH_SEGMENT = "expo-wear-report"

    private const val DATA_JSON_KEY = "json"
  }
}
