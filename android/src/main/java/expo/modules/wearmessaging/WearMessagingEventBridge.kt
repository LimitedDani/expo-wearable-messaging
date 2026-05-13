package expo.modules.wearmessaging

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.ArrayDeque

/**
 * Forwards watch → phone payloads to JS on the main thread.
 *
 * Events can arrive in [WearMessagingListenerService] before the Expo module registers
 * [emitter] — we queue them and flush when [setEmitter] runs.
 *
 * Message API + Data Layer may deliver duplicates; we dedupe briefly.
 */
internal object WearMessagingEventBridge {
  private val mainHandler = Handler(Looper.getMainLooper())
  private var emitter: ((String) -> Unit)? = null
  private val pending = ArrayDeque<String>()
  private const val MAX_PENDING = 32
  private var lastPayload: String? = null
  private var lastEmittedAtMs: Long = 0L

  fun setEmitter(block: (String) -> Unit) {
    mainHandler.post {
      emitter = block
      flushPendingLocked()
    }
  }

  fun clearEmitter() {
    mainHandler.post {
      emitter = null
      pending.clear()
    }
  }

  /** Must run on main thread; caller [setEmitter] runs inside mainHandler.post. */
  private fun flushPendingLocked() {
    val cb = emitter ?: return
    while (true) {
      val json =
        synchronized(pending) {
          if (pending.isEmpty()) return
          pending.removeFirst()
        }
      deliverNow(cb, json)
    }
  }

  private fun deliverNow(cb: (String) -> Unit, json: String) {
    val now = SystemClock.elapsedRealtime()
    if (json == lastPayload && now - lastEmittedAtMs < 600L) {
      return
    }
    lastPayload = json
    lastEmittedAtMs = now
    cb.invoke(json)
  }

  fun emitPayload(json: String) {
    mainHandler.post {
      val cb = emitter
      if (cb != null) {
        deliverNow(cb, json)
      } else {
        synchronized(pending) {
          while (pending.size >= MAX_PENDING) {
            pending.removeFirst()
          }
          pending.addLast(json)
        }
      }
    }
  }
}
