/**
 * Portal-ish host (`implementation-plan.md` §2) — mounted once in `Layout.tsx`, outside
 * `<Outlet/>`, so a result moment shows up no matter which page is on screen (result-moment.md
 * §1: "не привязан к странице"). Subscribes to `resultEventBus`, feeds a `ResultQueue`, renders
 * `ResultCard` for whatever `ResultQueue.current()` says is on screen.
 */
import { useEffect, useRef, useState } from "react";
import { subscribe } from "./resultEventBus";
import { ResultQueue, type DisplayItem } from "./resultQueue";
import { ResultCard } from "./ResultCard";

export function ResultMomentHost() {
  const queueRef = useRef(new ResultQueue());
  const [display, setDisplay] = useState<DisplayItem | null>(null);

  useEffect(() => {
    return subscribe((event) => {
      queueRef.current.push(event);
      setDisplay(queueRef.current.current());
    });
  }, []);

  if (!display) return null;

  const advance = () => {
    queueRef.current.advance();
    setDisplay(queueRef.current.current());
  };

  return <ResultCard display={display} onDismiss={advance} />;
}
