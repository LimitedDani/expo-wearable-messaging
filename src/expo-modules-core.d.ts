declare module "expo-modules-core" {
  export type EventsMap = Record<string, unknown>;

  export type NativeModule<TEvents extends EventsMap = Record<never, never>> = {
    addListener<K extends keyof TEvents>(
      eventName: K,
      listener: TEvents[K],
    ): { remove(): void };
  } & Record<string, unknown>;

  export function requireNativeModule<T>(moduleName: string): T;
}
