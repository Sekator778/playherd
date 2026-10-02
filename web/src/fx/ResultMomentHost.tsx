/**
 * Portal-ish host (`implementation-plan.md` §2) — mounted once in `Layout.tsx`, outside
 * `<Outlet/>`, so a result moment shows up no matter which page is on screen (result-moment.md
 * §1: "не привязан к странице"). Subscribes to `resultEventBus`, feeds a `ResultQueue`, renders
 * `ResultCard` for whatever `ResultQueue.current()` says is on screen.
 *
 * A `quiet` event (the settlement of a liquidation the player was already told about) does not go through
 * the queue at all: it is a one-line note (`QuietNotes`) beside whatever moment is on screen, so it can neither
 * delay a real moment nor be one.
 */
import { useCallback, useEffect, useRef, useState } from "react";
import { subscribe } from "./resultEventBus";
import { ResultQueue, type DisplayItem } from "./resultQueue";
import { ResultCard } from "./ResultCard";
import { QuietNotes } from "./QuietNotes";
import type { ResultEvent } from "./resultEvent";

export function ResultMomentHost() {
  const queueRef = useRef(new ResultQueue());
  const [display, setDisplay] = useState<DisplayItem | null>(null);
  const [notes, setNotes] = useState<ResultEvent[]>([]);

  useEffect(() => {
    return subscribe((event) => {
      if (event.quiet) {
        setNotes((current) => (current.some((n) => n.id === event.id) ? current : [...current, event]));
        return;
      }
      queueRef.current.push(event);
      setDisplay(queueRef.current.current());
    });
  }, []);

  const dismissNote = useCallback((id: string) => setNotes((current) => current.filter((n) => n.id !== id)), []);

  const advance = () => {
    queueRef.current.advance();
    setDisplay(queueRef.current.current());
  };

  return (
    <>
      {display && <ResultCard display={display} onDismiss={advance} />}
      <QuietNotes notes={notes} onDismiss={dismissNote} />
    </>
  );
}
