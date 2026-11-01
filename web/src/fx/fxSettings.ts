/**
 * Sound/haptics toggles (`docs/product/design/direction.md` §6): `localStorage`-persisted,
 * `sfxEnabled` default `false`, `hapticsEnabled` default `true`. A tiny same-tab pub/sub sits
 * alongside the storage read/write because the header's toggle and the result card's own toggle
 * (direction.md §6: "again... via a small icon on the result moment card itself") both need to
 * reflect a change the other one made, and the browser's own `storage` event only fires
 * cross-tab, never within the tab that made the change.
 */
import { useEffect, useReducer } from "react";

const SFX_KEY = "herd:sfxEnabled";
const HAPTICS_KEY = "herd:hapticsEnabled";

type Listener = () => void;
const listeners = new Set<Listener>();

function readBool(key: string, defaultValue: boolean): boolean {
  try {
    const v = localStorage.getItem(key);
    return v === null ? defaultValue : v === "true";
  } catch {
    return defaultValue;
  }
}

function writeBool(key: string, value: boolean): void {
  try {
    localStorage.setItem(key, String(value));
  } catch {
    // Private browsing / blocked storage — the setting just won't persist across reloads; the
    // in-memory listeners below still update this session's UI.
  }
  listeners.forEach((listener) => listener());
}

// `/lab`'s "force sound/haptics on" checkboxes (implementation-plan.md §5: "only for preview on
// this page, does not touch the real fxSettings in localStorage") — an in-memory override, checked
// before the persisted value, that never touches `localStorage`.
let previewSfxOverride: boolean | null = null;
let previewHapticsOverride: boolean | null = null;

export function setPreviewOverrides(overrides: { sfx?: boolean | null; haptics?: boolean | null }): void {
  if (overrides.sfx !== undefined) previewSfxOverride = overrides.sfx;
  if (overrides.haptics !== undefined) previewHapticsOverride = overrides.haptics;
  listeners.forEach((listener) => listener());
}

export function getSfxEnabled(): boolean {
  return previewSfxOverride ?? readBool(SFX_KEY, false);
}

export function getHapticsEnabled(): boolean {
  return previewHapticsOverride ?? readBool(HAPTICS_KEY, true);
}

export function setSfxEnabled(value: boolean): void {
  writeBool(SFX_KEY, value);
}

export function setHapticsEnabled(value: boolean): void {
  writeBool(HAPTICS_KEY, value);
}

export interface FxSettings {
  sfxEnabled: boolean;
  hapticsEnabled: boolean;
  setSfxEnabled: (value: boolean) => void;
  setHapticsEnabled: (value: boolean) => void;
}

export function useFxSettings(): FxSettings {
  const [, forceUpdate] = useReducer((n: number) => n + 1, 0);
  useEffect(() => {
    listeners.add(forceUpdate);
    return () => {
      listeners.delete(forceUpdate);
    };
  }, []);
  return {
    sfxEnabled: getSfxEnabled(),
    hapticsEnabled: getHapticsEnabled(),
    setSfxEnabled,
    setHapticsEnabled,
  };
}
