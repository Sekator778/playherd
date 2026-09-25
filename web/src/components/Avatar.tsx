/**
 * A small colored initial circle next to a player's name — leaderboard cards, the round "stage"
 * (`docs/product/design/direction.md` §4). Purely decorative variety between players in the same
 * list; the nickname/BOT badge text right next to it is always the real identity (this never
 * carries meaning on its own — no honesty rule applies to decoration, only to outcome signals).
 */
const PALETTE = ["#9147ff", "#3ea6ff", "#00b8d9", "#f2b84b", "#7c93ff", "#c081ff"];

function hashString(s: string): number {
  let h = 0;
  for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) >>> 0;
  return h;
}

export function Avatar({ name, className = "" }: { name: string; className?: string }) {
  const trimmed = name.trim();
  const initial = trimmed.length > 0 ? trimmed[0].toUpperCase() : "?";
  const color = PALETTE[hashString(trimmed) % PALETTE.length];
  return (
    <span className={`avatar ${className}`} style={{ background: color }} aria-hidden="true">
      {initial}
    </span>
  );
}
