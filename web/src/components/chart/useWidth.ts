import { useEffect, useState } from "react";

/** The width of the element a callback ref is attached to. A callback ref (and state for the element), not
 *  a ref object read in a mount effect: the plot's container only exists once there are prices to draw, so
 *  the observer has to attach whenever that element appears — an observer set up at mount never saw it, and
 *  the chart stayed at its fallback width inside a wider box. */
export function useWidth(): [(element: HTMLDivElement | null) => void, number] {
  const [element, setElement] = useState<HTMLDivElement | null>(null);
  const [width, setWidth] = useState(0);
  useEffect(() => {
    if (!element || typeof ResizeObserver === "undefined") return;
    const observer = new ResizeObserver((entries) => {
      const w = Math.round(entries[0]?.contentRect.width ?? 0);
      if (w > 0) setWidth(w);
    });
    observer.observe(element);
    return () => observer.disconnect();
  }, [element]);
  return [setElement, width];
}
