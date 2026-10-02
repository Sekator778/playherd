/**
 * Haptics — `navigator.vibrate` only. `implementation-plan.md` §2 designs a
 * `Capacitor.isNativePlatform()` → dynamic `import("@capacitor/haptics")` path for a richer native
 * feel on the Android build; this pass does not add that dependency (`@capacitor/haptics` is not
 * in `web/package.json`, and wiring it in means `npx cap sync android` + a debug-APK rebuild
 * across three emulators, per the task's own instructions — a separate, riskier change than this
 * pass's scope). `navigator.vibrate` covers both the web/PWA build and the Android WebView (best
 * effort — some WebViews restrict it) with zero new dependencies. предложение: adding the native
 * plugin is a good follow-up, tracked here rather than attempted silently.
 */
import { getHapticsEnabled } from "./fxSettings";

export type ImpactStyle = "light" | "medium" | "heavy";
export type NotificationType = "success" | "warning" | "error";

const IMPACT_PATTERN: Record<ImpactStyle, number> = { light: 15, medium: 30, heavy: 45 };
const NOTIFICATION_PATTERN: Record<NotificationType, number[]> = {
  success: [10, 40, 15],
  warning: [30, 20, 30],
  error: [40, 30, 40, 30, 40],
};

/**
 * A browser lets a page vibrate only after the player has touched it once (Chrome: "Blocked call to navigator.vibrate because
 * user hasn't tapped on the frame" — an ERROR-level console line, on every result moment of a page nobody has tapped yet, as
 * a settlement arriving while the player just watches). Where the page can tell (`navigator.userActivation`), it does not
 * call at all until then; where it cannot (an older WebView), it calls as before.
 */
function mayVibrate(): boolean {
  const activation = (navigator as Navigator & { userActivation?: { hasBeenActive: boolean } }).userActivation;
  return activation === undefined || activation.hasBeenActive;
}

function vibrateNow(pattern: number | number[]): void {
  if (!getHapticsEnabled() || !mayVibrate()) return;
  try {
    navigator.vibrate?.(pattern);
  } catch {
    // Unsupported / blocked — silently a no-op, never surfaced to the player.
  }
}

export function impact(style: ImpactStyle): void {
  vibrateNow(IMPACT_PATTERN[style]);
}

export function notification(type: NotificationType): void {
  vibrateNow(NOTIFICATION_PATTERN[type]);
}

/** A raw pattern for a spec table that gives one directly (result-moment.md/live-round.md's
 *  `vibrate([...])` columns). */
export function vibrate(pattern: number | number[]): void {
  vibrateNow(pattern);
}
