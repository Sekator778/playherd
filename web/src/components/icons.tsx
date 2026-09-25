/** Small glyphs used alongside direction text — never the only signal for UP/DOWN (accessibility
 *  requirement: no color-only meaning). `aria-hidden` because the adjacent text already says
 *  "UP"/"DOWN"/"Won"/etc. */
export function UpGlyph({ className = "" }: { className?: string }) {
  return (
    <span className={className} aria-hidden="true">
      ▲
    </span>
  );
}

export function DownGlyph({ className = "" }: { className?: string }) {
  return (
    <span className={className} aria-hidden="true">
      ▼
    </span>
  );
}

export function DirectionGlyph({ direction, className }: { direction: "UP" | "DOWN"; className?: string }) {
  return direction === "UP" ? <UpGlyph className={className} /> : <DownGlyph className={className} />;
}
