/**
 * Who gets the Android hardware Back button first. A sheet or a dialog that is open must close on Back — not leave
 * the screen behind it (or, on the Lobby, exit the app): the person pressed Back to get out of the sheet. Added with the
 * feedback sheet (Р-81, `components/FeedbackModal.tsx`); `useAndroidBackButton.ts` asks here before it navigates.
 *
 * A stack: the sheet opened last answers first, and a press goes to exactly one handler. No handler registered → Back
 * does what it always did. On the web build nothing calls {@link handleBackPress} (the browser's own Back is the
 * browser's), so a registration there is inert.
 */
import { useEffect, useRef } from "react";

type BackHandler = () => void;

const handlers: BackHandler[] = [];

/** Makes `handler` the first to answer Back; returns the function that takes it off again. */
export function registerBackHandler(handler: BackHandler): () => void {
  handlers.push(handler);
  return () => {
    const index = handlers.lastIndexOf(handler);
    if (index !== -1) handlers.splice(index, 1);
  };
}

/** A hardware Back press: runs the topmost handler and says whether there was one (`false` → the caller navigates). */
export function handleBackPress(): boolean {
  const top = handlers[handlers.length - 1];
  if (!top) return false;
  top();
  return true;
}

/** While `active`, a hardware Back press calls `onBack` (the latest one given) instead of navigating. */
export function useBackHandler(active: boolean, onBack: () => void): void {
  const latest = useRef(onBack);
  useEffect(() => {
    latest.current = onBack;
  });
  useEffect(() => {
    if (!active) return;
    return registerBackHandler(() => latest.current());
  }, [active]);
}
