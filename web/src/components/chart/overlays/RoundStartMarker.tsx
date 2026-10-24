import type { EpochMs } from "../../../api/contract";
import { inView, type Frame } from "../frame";

/** A faint vertical marker where the running round's open price was fixed. Nothing when it is out of view. */
export function RoundStartMarker({ frame, roundStartMs }: { frame: Frame; roundStartMs: EpochMs | null | undefined }) {
  if (!inView(frame, roundStartMs)) return null;
  const x = frame.x(roundStartMs);
  return <line x1={x} x2={x} y1={frame.pad.top} y2={frame.pad.top + frame.plotH} className="pchart__start" />;
}
