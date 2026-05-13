/* eslint-disable import/no-unresolved -- peer `expo-modules-core` resolved by host app */
import { requireNativeModule } from "expo-modules-core";
import { Platform } from "react-native";

const MODULE_NAME = "ExpoWearableMessaging";

/**
 * Default Android Message API path used by the native module. Use the same value in your Wear OS
 * app when calling `MessageClient.sendMessage` / handling `onMessageReceived`.
 */
export const ANDROID_DEFAULT_MESSAGE_PATH = "/expo-wear-msg" as const;

/**
 * Default segment matched on Data Layer URI paths when the phone receives mirrored JSON (see
 * Android `WearMessagingListenerService`). Use a path containing this segment on the watch, e.g.
 * `/expo-wear-report`.
 */
export const ANDROID_DEFAULT_DATA_REPORT_PATH_SEGMENT = "expo-wear-report" as const;

/** Native surface is shared across iOS and Android; OS-specific methods may no-op or return defaults. */
export type ExpoWearableMessagingNative = {
  sendMessageAsync(jsonPayload: string): Promise<string | null | undefined | void>;
  refreshReachabilityAsync(): Promise<boolean>;
  isReachableAsync(): Promise<boolean>;
  getIsPairedAsync(): Promise<boolean>;
  getIsCompanionInstalledAsync(): Promise<boolean>;
  addListener(
    eventName: "onMessage" | "onReachability" | "onPaired" | "onInstalled",
    listener: (event: Record<string, unknown>) => void,
  ): { remove(): void };
};

function getNative(): ExpoWearableMessagingNative | null {
  if (Platform.OS !== "ios" && Platform.OS !== "android") {
    return null;
  }
  try {
    return requireNativeModule<ExpoWearableMessagingNative>(MODULE_NAME);
  } catch (err) {
    if (__DEV__) {
      console.warn(
        `[expo-wearable-messaging] Native module "${MODULE_NAME}" not linked or failed to load:`,
        err,
      );
    }
    return null;
  }
}

/** Send a JSON object to the companion watch; resolves with the reply payload when the OS supports it (interactive send on iOS). */
export async function sendJsonMessage(
  payload: Record<string, unknown>,
): Promise<Record<string, unknown>> {
  const native = getNative();
  if (!native) {
    if (__DEV__) {
      console.warn(
        "[expo-wearable-messaging] sendJsonMessage: native module missing — rebuild iOS with pods (expo-wearable-messaging).",
      );
    }
    return {};
  }
  const json = JSON.stringify(payload);
  const result = await native.sendMessageAsync(json);
  if (Platform.OS === "android") {
    return {};
  }
  const replyJson =
    typeof result === "string" && result.length > 0 ? result : "{}";
  try {
    return JSON.parse(replyJson) as Record<string, unknown>;
  } catch {
    return {};
  }
}

export function addMessageListener(
  listener: (payload: Record<string, unknown>) => void,
): () => void {
  const native = getNative();
  if (!native) {
    return () => {};
  }
  const subscription = native.addListener(
    "onMessage",
    (event: Record<string, unknown>) => {
      const { json } = event;
      if (typeof json !== "string" || json.length === 0) {
        return;
      }
      try {
        listener(JSON.parse(json) as Record<string, unknown>);
      } catch {
        /* ignore malformed */
      }
    },
  );
  return () => subscription.remove();
}

/** Fires when reachability changes; `connected` matches Wear OS Data Layer semantics (watch reachable). */
export function addReachabilityListener(
  listener: (connected: boolean) => void,
): () => void {
  const native = getNative();
  if (!native) {
    return () => {};
  }
  const subscription = native.addListener(
    "onReachability",
    (event: Record<string, unknown>) => {
      listener(Boolean(event.connected));
    },
  );
  return () => subscription.remove();
}

export function addPairedListener(
  listener: (paired: boolean) => void,
): () => void {
  const native = getNative();
  if (!native) {
    return () => {};
  }
  const subscription = native.addListener(
    "onPaired",
    (event: Record<string, unknown>) => {
      listener(Boolean(event.paired));
    },
  );
  return () => subscription.remove();
}

export function addCompanionInstalledListener(
  listener: (installed: boolean) => void,
): () => void {
  const native = getNative();
  if (!native) {
    return () => {};
  }
  const subscription = native.addListener(
    "onInstalled",
    (event: Record<string, unknown>) => {
      listener(Boolean(event.installed));
    },
  );
  return () => subscription.remove();
}

export function refreshReachability(): Promise<boolean> {
  const native = getNative();
  if (!native) {
    return Promise.resolve(false);
  }
  return native.refreshReachabilityAsync();
}

export function getReachable(): Promise<boolean> {
  const native = getNative();
  if (!native) {
    return Promise.resolve(false);
  }
  return native.isReachableAsync();
}

/** @deprecated Use {@link getReachable}; kept for call sites that still ask for “reachability”. */
export const getReachability = getReachable;

export function getPaired(): Promise<boolean> {
  const native = getNative();
  if (!native) {
    return Promise.resolve(false);
  }
  return native.getIsPairedAsync();
}

export function getCompanionInstalled(): Promise<boolean> {
  const native = getNative();
  if (!native) {
    return Promise.resolve(false);
  }
  return native.getIsCompanionInstalledAsync();
}
