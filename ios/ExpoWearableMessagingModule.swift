import ExpoModulesCore
import WatchConnectivity

/// On **device**, use system flags as-is. On **iPhone Simulator**, WatchConnectivity often reports
/// `isPaired`, `isWatchAppInstalled`, and `isReachable` all `false` even while the watchOS simulator
/// is running — once `activationState == .activated`, treat the session as ready so companion watch UI is usable.
private func sessionSnapshot(_ session: WCSession) -> (paired: Bool, installed: Bool, reachable: Bool) {
  let activated = session.activationState == .activated
  let sysInstalled = session.isWatchAppInstalled
  let sysPaired = session.isPaired
  let sysReachable = session.isReachable

  #if targetEnvironment(simulator)
    guard activated else {
      return (false, false, false)
    }
    let installed = sysInstalled || activated
    let paired = sysPaired || sysInstalled || activated
    let reachable = sysReachable || activated
    return (paired, installed, reachable)
  #else
    return (sysPaired, sysInstalled, sysReachable)
  #endif
}

private func jsonString(from dict: [String: Any]) -> String {
  guard JSONSerialization.isValidJSONObject(dict),
        let data = try? JSONSerialization.data(withJSONObject: dict, options: []),
        let string = String(data: data, encoding: .utf8)
  else {
    return "{}"
  }
  return string
}

private func parseJsonDictionary(_ json: String) throws -> [String: Any] {
  guard let data = json.data(using: .utf8) else {
    throw NSError(domain: "ExpoWearableMessaging", code: 1, userInfo: [NSLocalizedDescriptionKey: "Invalid UTF-8"])
  }
  let obj = try JSONSerialization.jsonObject(with: data, options: [])
  guard let dict = obj as? [String: Any] else {
    throw NSError(domain: "ExpoWearableMessaging", code: 2, userInfo: [NSLocalizedDescriptionKey: "JSON root must be an object"])
  }
  return dict
}

private final class WatchSessionDelegate: NSObject, WCSessionDelegate {
  weak var owner: ExpoWearableMessagingModule?

  func session(
    _ session: WCSession,
    activationDidCompleteWith activationState: WCSessionActivationState,
    error: Error?
  ) {
    DispatchQueue.main.async {
      #if targetEnvironment(simulator)
        // Simulator sometimes delivers a non-nil error while the session is still usable for dev.
        if activationState == .activated {
          self.owner?.emitFullSessionStatus(session)
          return
        }
        self.owner?.clearSessionCache()
        self.owner?.sendEvent("onReachability", ["connected": false])
      #else
        guard error == nil, activationState == .activated else {
          self.owner?.clearSessionCache()
          self.owner?.sendEvent("onReachability", ["connected": false])
          return
        }
        self.owner?.emitFullSessionStatus(session)
      #endif
    }
  }

  /// Required on **iOS** for paired-watch lifecycle; **unavailable on watchOS** — do not implement there.
  #if os(iOS)
    func sessionDidBecomeInactive(_ session: WCSession) {}

    func sessionDidDeactivate(_ session: WCSession) {
      DispatchQueue.main.async {
        self.owner?.clearSessionCache()
        self.owner?.sendEvent("onReachability", ["connected": false])
        session.activate()
      }
    }
  #endif

  func sessionReachabilityDidChange(_ session: WCSession) {
    DispatchQueue.main.async {
      self.owner?.emitReachabilityOnly(session)
    }
  }

  func session(_ session: WCSession, didReceiveMessage message: [String: Any]) {
    DispatchQueue.main.async {
      self.owner?.sendEvent("onMessage", ["json": jsonString(from: message)])
    }
  }

  func session(
    _ session: WCSession,
    didReceiveMessage message: [String: Any],
    replyHandler: @escaping ([String: Any]) -> Void
  ) {
    DispatchQueue.main.async {
      self.owner?.sendEvent("onMessage", ["json": jsonString(from: message)])
      replyHandler([:])
    }
  }

  func session(_ session: WCSession, didReceiveApplicationContext applicationContext: [String: Any]) {
    DispatchQueue.main.async {
      self.owner?.sendEvent("onMessage", ["json": jsonString(from: applicationContext)])
    }
  }
}

public final class ExpoWearableMessagingModule: Module {
  private let watchDelegate = WatchSessionDelegate()

  /// Last snapshot from delegate (avoids reading `WCSession` before activation completes).
  private var cachedPaired = false
  private var cachedInstalled = false
  private var cachedReachable = false

  public func definition() -> ModuleDefinition {
    Name("ExpoWearableMessaging")

    Events(
      "onMessage",
      "onReachability",
      "onPaired",
      "onInstalled"
    )

    OnCreate {
      self.watchDelegate.owner = self
      // WCSession must be activated on the main queue.
      DispatchQueue.main.async {
        guard WCSession.isSupported() else {
          return
        }
        let session = WCSession.default
        session.delegate = self.watchDelegate
        session.activate()
        #if targetEnvironment(simulator)
          // Simulator WC activation is flaky; a second nudge often helps after both runtimes are up.
          DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) {
            WCSession.default.activate()
          }
        #endif
      }
    }

    AsyncFunction("sendMessageAsync") { (jsonPayload: String, promise: Promise) in
      DispatchQueue.main.async {
        guard WCSession.isSupported() else {
          promise.reject("E_UNSUPPORTED", "WatchConnectivity is not supported on this device")
          return
        }

        func parseAndDeliver(using session: WCSession) {
          let dict: [String: Any]
          do {
            dict = try parseJsonDictionary(jsonPayload)
          } catch {
            promise.reject("E_INVALID_JSON", error.localizedDescription)
            return
          }

          // WatchConnectivity only delivers application context to the watch when the dictionary
          // *differs* from the previous update — add a token so every send can fire the delegate.
          var contextPayload = dict
          contextPayload["_wcSeq"] = Date().timeIntervalSince1970

          #if targetEnvironment(simulator)
            // Interactive `sendMessage` is unreliable on simulators; context updates are the
            // dependable path (watch: `didReceiveApplicationContext`).
            do {
              try session.updateApplicationContext(contextPayload)
              promise.resolve("{}")
            } catch {
              promise.reject("E_CONTEXT", error.localizedDescription)
            }
            return
          #endif

          if session.isReachable {
            session.sendMessage(dict, replyHandler: { reply in
              promise.resolve(jsonString(from: reply))
            }, errorHandler: { _ in
              do {
                try session.updateApplicationContext(contextPayload)
                promise.resolve("{}")
              } catch {
                promise.reject("E_CONTEXT", error.localizedDescription)
              }
            })
          } else {
            do {
              try session.updateApplicationContext(contextPayload)
              promise.resolve("{}")
            } catch {
              promise.reject("E_CONTEXT", error.localizedDescription)
            }
          }
        }

        func sendWithActivationRetries(attempt: Int) {
          let session = WCSession.default
          if session.activationState == .activated {
            parseAndDeliver(using: session)
            return
          }
          #if targetEnvironment(simulator)
            if attempt < 4 {
              session.activate()
              DispatchQueue.main.asyncAfter(deadline: .now() + 0.9) {
                sendWithActivationRetries(attempt: attempt + 1)
              }
              return
            }
            promise.reject(
              "E_SESSION",
              "WCSession not activated. Run iPhone + Watch simulators from Xcode with the watch app installed, then retry."
            )
          #else
            promise.reject("E_SESSION", "WCSession is not activated yet")
          #endif
        }

        sendWithActivationRetries(attempt: 0)
      }
    }

    AsyncFunction("refreshReachabilityAsync") { () -> Bool in
      guard WCSession.isSupported() else { return false }
      let session = WCSession.default
      guard session.activationState == .activated else {
        return false
      }
      let snap = sessionSnapshot(session)
      self.cachedPaired = snap.paired
      self.cachedInstalled = snap.installed
      self.cachedReachable = snap.reachable
      return snap.reachable
    }

    AsyncFunction("isReachableAsync") { () -> Bool in
      guard WCSession.isSupported() else { return false }
      let session = WCSession.default
      if session.activationState != .activated {
        return false
      }
      let snap = sessionSnapshot(session)
      self.cachedReachable = snap.reachable
      return snap.reachable
    }

    AsyncFunction("getIsPairedAsync") { () -> Bool in
      guard WCSession.isSupported() else { return false }
      let session = WCSession.default
      if session.activationState != .activated {
        return self.cachedPaired
      }
      let snap = sessionSnapshot(session)
      self.cachedPaired = snap.paired
      return snap.paired
    }

    AsyncFunction("getIsCompanionInstalledAsync") { () -> Bool in
      guard WCSession.isSupported() else { return false }
      let session = WCSession.default
      if session.activationState != .activated {
        return self.cachedInstalled
      }
      let snap = sessionSnapshot(session)
      self.cachedInstalled = snap.installed
      return snap.installed
    }
  }

  fileprivate func emitFullSessionStatus(_ session: WCSession) {
    let snap = sessionSnapshot(session)
    cachedPaired = snap.paired
    cachedInstalled = snap.installed
    cachedReachable = snap.reachable
    sendEvent("onPaired", ["paired": snap.paired])
    sendEvent("onInstalled", ["installed": snap.installed])
    sendEvent("onReachability", ["connected": snap.reachable])
  }

  fileprivate func emitReachabilityOnly(_ session: WCSession) {
    let snap = sessionSnapshot(session)
    cachedReachable = snap.reachable
    cachedPaired = snap.paired
    cachedInstalled = snap.installed
    sendEvent("onReachability", ["connected": snap.reachable])
  }

  fileprivate func clearSessionCache() {
    cachedPaired = false
    cachedInstalled = false
    cachedReachable = false
  }
}
