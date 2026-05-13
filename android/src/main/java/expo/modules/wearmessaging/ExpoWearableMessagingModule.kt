package expo.modules.wearmessaging

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import expo.modules.kotlin.Promise
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import java.nio.charset.StandardCharsets

/**
 * Phone-side Wear OS messaging. JS module name matches iOS: [ExpoWearableMessaging].
 * Events: `onMessage` ({ json }), `onReachability` ({ connected }).
 *
 * Incoming payloads are primarily delivered by [WearMessagingListenerService] (messages + data
 * items). We also register [MessageClient.addListener] while the module is alive as a backup.
 */
class ExpoWearableMessagingModule :
  Module(),
  MessageClient.OnMessageReceivedListener,
  CapabilityClient.OnCapabilityChangedListener {

  private val mainHandler = Handler(Looper.getMainLooper())
  private val connectedNodes = mutableSetOf<Node>()
  private var wearContext: Context? = null
  private var messageClient: MessageClient? = null
  private var capabilityClient: CapabilityClient? = null

  override fun definition() =
    ModuleDefinition {
      Name("ExpoWearableMessaging")

      Events("onMessage", "onReachability", "onPaired", "onInstalled")

      OnCreate {
        val androidContext =
          appContext.reactContext?.applicationContext
            ?: appContext.currentActivity?.applicationContext
            ?: return@OnCreate
        wearContext = androidContext.applicationContext
        val ctx = wearContext!!
        WearMessagingEventBridge.setEmitter { json ->
          sendEvent("onMessage", mapOf("json" to json))
        }
        messageClient =
          Wearable.getMessageClient(ctx).also { client ->
            client.addListener(this@ExpoWearableMessagingModule)
          }
        capabilityClient =
          Wearable.getCapabilityClient(ctx).also { client ->
            client.addLocalCapability(LOCAL_CAPABILITY_PHONE)
            client.addListener(this@ExpoWearableMessagingModule, TARGET_CAPABILITY_WATCH)
          }
        mergeDiscovery()
      }

      OnActivityEntersForeground {
        mergeDiscovery()
      }

      OnDestroy {
        messageClient?.removeListener(this@ExpoWearableMessagingModule)
        WearMessagingEventBridge.clearEmitter()
        capabilityClient?.removeLocalCapability(LOCAL_CAPABILITY_PHONE)
        capabilityClient?.removeListener(this@ExpoWearableMessagingModule)
        messageClient = null
        capabilityClient = null
        wearContext = null
        connectedNodes.clear()
      }

      AsyncFunction("sendMessageAsync") { jsonPayload: String, promise: Promise ->
        try {
          val client = messageClient
          val node = pickTargetNode()
          if (client == null || node == null) {
            promise.reject("E_NO_WATCH", "No reachable Wear OS node", null)
            return@AsyncFunction
          }
          val bytes = jsonPayload.toByteArray(StandardCharsets.UTF_8)
          client
            .sendMessage(node.id, DEFAULT_MESSAGE_PATH, bytes)
            .addOnSuccessListener { promise.resolve(null) }
            .addOnFailureListener { e -> promise.reject("E_SEND_FAILED", e.message, e) }
        } catch (e: Exception) {
          promise.reject("E_SEND_FAILED", e.message, e)
        }
      }

      AsyncFunction("refreshReachabilityAsync") { promise: Promise ->
        mergeDiscovery { connected ->
          promise.resolve(connected)
        }
      }

      AsyncFunction("isReachableAsync") { promise: Promise ->
        promise.resolve(connectedNodes.isNotEmpty())
      }

      /** Always false on Wear OS phone; present so JS can call the same API as iOS. */
      AsyncFunction("getIsPairedAsync") { promise: Promise ->
        promise.resolve(false)
      }

      /** Always false on Wear OS phone; present so JS can call the same API as iOS. */
      AsyncFunction("getIsCompanionInstalledAsync") { promise: Promise ->
        promise.resolve(false)
      }
    }

  override fun onMessageReceived(messageEvent: MessageEvent) {
    val payload = messageEvent.data ?: return
    if (payload.isEmpty()) {
      return
    }
    val json = String(payload, StandardCharsets.UTF_8)
    WearMessagingEventBridge.emitPayload(json)
  }

  override fun onCapabilityChanged(capabilityInfo: CapabilityInfo) {
    val ctx = wearContext ?: return
    Wearable.getNodeClient(ctx).connectedNodes
      .addOnSuccessListener { connected ->
        replaceConnectedNodes(mergeNodes(capabilityInfo.nodes, connected))
      }
      .addOnFailureListener {
        replaceConnectedNodes(capabilityInfo.nodes)
      }
  }

  private fun mergeDiscovery(completion: ((Boolean) -> Unit)? = null) {
    val ctx = wearContext
    val cc = capabilityClient
    if (ctx == null || cc == null) {
      completion?.let { c -> mainHandler.post { c(false) } }
      return
    }
    val nc = Wearable.getNodeClient(ctx)
    cc.getCapability(TARGET_CAPABILITY_WATCH, CapabilityClient.FILTER_ALL)
      .addOnSuccessListener { info ->
        nc.connectedNodes
          .addOnSuccessListener { connected ->
            replaceConnectedNodes(mergeNodes(info.nodes, connected), completion)
          }
          .addOnFailureListener {
            replaceConnectedNodes(info.nodes, completion)
          }
      }
      .addOnFailureListener {
        nc.connectedNodes
          .addOnSuccessListener { connected ->
            replaceConnectedNodes(connected, completion)
          }
          .addOnFailureListener {
            completion?.let { c -> mainHandler.post { c(false) } }
          }
      }
  }

  private fun mergeNodes(
    capabilityNodes: Collection<Node>,
    connected: Collection<Node>,
  ): Set<Node> =
    buildSet {
      addAll(capabilityNodes)
      addAll(connected)
    }

  private fun replaceConnectedNodes(
    nodes: Collection<Node>,
    completion: ((Boolean) -> Unit)? = null,
  ) {
    mainHandler.post {
      connectedNodes.clear()
      connectedNodes.addAll(nodes)
      sendEvent(
        "onReachability",
        mapOf("connected" to connectedNodes.isNotEmpty()),
      )
      completion?.invoke(connectedNodes.isNotEmpty())
    }
  }

  private fun pickTargetNode(): Node? {
    val nearby = connectedNodes.firstOrNull { it.isNearby }
    if (nearby != null) {
      return nearby
    }
    return connectedNodes.firstOrNull()
  }

  companion object {
    /** Message API path; must match your Wear OS companion app send/receive path. */
    const val DEFAULT_MESSAGE_PATH = "/expo-wear-msg"

    private const val LOCAL_CAPABILITY_PHONE = "phone"
    private const val TARGET_CAPABILITY_WATCH = "watch"
  }
}
