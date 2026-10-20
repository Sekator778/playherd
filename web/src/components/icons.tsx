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

/**
 * Outcome icons (docs/product/design/direction.md §3 "Outcome chips") — same inline-SVG pattern
 * as the glyphs above, `currentColor` so each sits in its own `.result-tile--*`/`.player-chip`
 * color. Always paired with colored text/a label next to it (never the only signal, per
 * `.claude/agents/game-designer.md`'s "Честность") — these are decoration for an outcome that is
 * already stated in words nearby, so `aria-hidden`.
 */
export function TrophyIcon({ className = "" }: { className?: string }) {
  return (
    <svg className={className} viewBox="0 0 24 24" width="20" height="20" fill="none" aria-hidden="true">
      <path
        d="M7 4h10v3.2A5 5 0 0 1 12 12.2 5 5 0 0 1 7 7.2V4Z"
        stroke="currentColor"
        strokeWidth="1.6"
        strokeLinejoin="round"
      />
      <path
        d="M7 5.2H4.2v.8a4 4 0 0 0 4 4M17 5.2H19.8v.8a4 4 0 0 1-4 4"
        stroke="currentColor"
        strokeWidth="1.6"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
      <path
        d="M12 12.2V15m-2.6 3h5.2m-4.5 0 .5-3h3l.5 3"
        stroke="currentColor"
        strokeWidth="1.6"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  );
}

/** Liquidation — a snapped crack, not a slow fracture (result-moment.md §2: "щелчок, не
 *  нарастание"). */
export function CrackIcon({ className = "" }: { className?: string }) {
  return (
    <svg className={className} viewBox="0 0 24 24" width="20" height="20" fill="none" aria-hidden="true">
      <circle cx="12" cy="12" r="9" stroke="currentColor" strokeWidth="1.4" opacity="0.5" />
      <path
        d="M12.5 4.5 9.5 11l3 1-2.5 7.5 6.5-9.5-3-1.2 2-3.3-3 .5Z"
        fill="currentColor"
        stroke="currentColor"
        strokeWidth="0.6"
        strokeLinejoin="round"
      />
    </svg>
  );
}

/** Plain loss/even — a neutral minus disc, never just red text with no shape. */
export function MinusDiscIcon({ className = "" }: { className?: string }) {
  return (
    <svg className={className} viewBox="0 0 24 24" width="20" height="20" fill="none" aria-hidden="true">
      <circle cx="12" cy="12" r="8.5" stroke="currentColor" strokeWidth="1.6" />
      <path d="M8.3 12h7.4" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
    </svg>
  );
}

/** EVEN — a neutral equals disc, distinct from `MinusDiscIcon`'s single bar (LOSS) so the two
 *  outcomes don't share a silhouette (result-moment.md §0's fourth, neutral kind). */
export function EqualsDiscIcon({ className = "" }: { className?: string }) {
  return (
    <svg className={className} viewBox="0 0 24 24" width="20" height="20" fill="none" aria-hidden="true">
      <circle cx="12" cy="12" r="8.5" stroke="currentColor" strokeWidth="1.6" />
      <path d="M8.3 10.2h7.4M8.3 13.8h7.4" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
    </svg>
  );
}

/** Sound/haptics toggle glyphs (direction.md §3 "same inline-SVG pattern...") — replace the raw
 *  platform emoji that used to sit in `Layout.tsx`'s `FxToggles` and `ResultCard.tsx`'s sound
 *  button; on Android those rendered as a small, visually indistinct dark badge rather than a
 *  recognizable icon (captured live on-device 2026-09-25). `currentColor` so each button's own
 *  text color drives it, same as every other icon on this page. */
export function SpeakerOnIcon({ className = "" }: { className?: string }) {
  return (
    <svg className={className} viewBox="0 0 24 24" width="16" height="16" fill="none" aria-hidden="true">
      <path d="M4 9.5v5h3.4L12 18V6L7.4 9.5H4Z" fill="currentColor" />
      <path
        d="M16 9.2a4 4 0 0 1 0 5.6M18.6 6.6a7.8 7.8 0 0 1 0 10.8"
        stroke="currentColor"
        strokeWidth="1.6"
        strokeLinecap="round"
      />
    </svg>
  );
}

export function SpeakerMuteIcon({ className = "" }: { className?: string }) {
  return (
    <svg className={className} viewBox="0 0 24 24" width="16" height="16" fill="none" aria-hidden="true">
      <path d="M4 9.5v5h3.4L12 18V6L7.4 9.5H4Z" fill="currentColor" />
      <path d="M16.2 9.8 20.2 14.2M20.2 9.8 16.2 14.2" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
    </svg>
  );
}

export function VibrationOnIcon({ className = "" }: { className?: string }) {
  return (
    <svg className={className} viewBox="0 0 24 24" width="16" height="16" fill="none" aria-hidden="true">
      <rect x="8.5" y="5" width="7" height="14" rx="1.6" stroke="currentColor" strokeWidth="1.6" />
      <path d="M4.5 9v6M2.2 10.5v3M19.5 9v6M21.8 10.5v3" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
    </svg>
  );
}

export function VibrationOffIcon({ className = "" }: { className?: string }) {
  return (
    <svg className={className} viewBox="0 0 24 24" width="16" height="16" fill="none" aria-hidden="true">
      <rect x="8.5" y="5" width="7" height="14" rx="1.6" stroke="currentColor" strokeWidth="1.6" />
      <path d="M5.5 5.5l13 13" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
    </svg>
  );
}

/** Wallet ledger per-entry-type icons (direction.md §3's inline-SVG pattern; §4 names Wallet's
 *  ledger as one of the "long list" screens this pattern applies to) — replaces the bare text
 *  label the ledger used to show for every row regardless of what kind of entry it was. */
export function GiftIcon({ className = "" }: { className?: string }) {
  return (
    <svg className={className} viewBox="0 0 24 24" width="18" height="18" fill="none" aria-hidden="true">
      <rect x="4" y="10" width="16" height="9" rx="1" stroke="currentColor" strokeWidth="1.6" />
      <path d="M4 10h16M12 10v9" stroke="currentColor" strokeWidth="1.6" />
      <path
        d="M12 10c-1.6-3.4-6-3-6-.4S9 10 12 10c3-0-6.4-2.8-6-.4S13.6 6.6 12 10Z"
        stroke="currentColor"
        strokeWidth="1.3"
        strokeLinejoin="round"
      />
    </svg>
  );
}

/** A poker-chip disc — staking PLAY on a round. */
export function ChipIcon({ className = "" }: { className?: string }) {
  return (
    <svg className={className} viewBox="0 0 24 24" width="18" height="18" fill="none" aria-hidden="true">
      <circle cx="12" cy="12" r="7.5" stroke="currentColor" strokeWidth="1.6" />
      <circle cx="12" cy="12" r="3.6" stroke="currentColor" strokeWidth="1.3" opacity="0.6" />
      <path d="M12 3.6v2.6M12 17.8v2.6M3.6 12h2.6M17.8 12h2.6" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
    </svg>
  );
}

/** A refund/undo arrow — shared by stake and withdrawal refunds. */
export function RefundIcon({ className = "" }: { className?: string }) {
  return (
    <svg className={className} viewBox="0 0 24 24" width="18" height="18" fill="none" aria-hidden="true">
      <path d="M4.6 8.6A8 8 0 1 1 4 12" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
      <path d="M6.2 5.4 4.6 8.6l3.4 1" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}

/** PLAY arriving into the balance (deposit). */
export function ArrowDownTrayIcon({ className = "" }: { className?: string }) {
  return (
    <svg className={className} viewBox="0 0 24 24" width="18" height="18" fill="none" aria-hidden="true">
      <path d="M12 4v10m0 0 3.4-3.4M12 14l-3.4-3.4" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M5 17.5h14" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" />
    </svg>
  );
}

/** PLAY leaving the balance (withdrawal). */
export function ArrowUpTrayIcon({ className = "" }: { className?: string }) {
  return (
    <svg className={className} viewBox="0 0 24 24" width="18" height="18" fill="none" aria-hidden="true">
      <path d="M12 14V4m0 0-3.4 3.4M12 4l3.4 3.4" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M5 17.5h14" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" />
    </svg>
  );
}

/** Bottom tab bar icons (task IA: "Lobby / Stats / Profile") — same inline-SVG,
 *  `currentColor`-driven pattern as every other icon in this file, so the tab bar's own
 *  active/inactive text color drives the icon too, no separate color prop needed. */
export function LobbyTabIcon({ className = "" }: { className?: string }) {
  return (
    <svg className={className} viewBox="0 0 24 24" width="22" height="22" fill="none" aria-hidden="true">
      <rect x="3.5" y="3.5" width="7" height="7" rx="1.6" stroke="currentColor" strokeWidth="1.6" />
      <rect x="13.5" y="3.5" width="7" height="7" rx="1.6" stroke="currentColor" strokeWidth="1.6" />
      <rect x="3.5" y="13.5" width="7" height="7" rx="1.6" stroke="currentColor" strokeWidth="1.6" />
      <rect x="13.5" y="13.5" width="7" height="7" rx="1.6" stroke="currentColor" strokeWidth="1.6" />
    </svg>
  );
}

export function StatsTabIcon({ className = "" }: { className?: string }) {
  return (
    <svg className={className} viewBox="0 0 24 24" width="22" height="22" fill="none" aria-hidden="true">
      <path d="M5 19V10M12 19V5M19 19v-6" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
      <path d="M4 19h16" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
    </svg>
  );
}

export function ProfileTabIcon({ className = "" }: { className?: string }) {
  return (
    <svg className={className} viewBox="0 0 24 24" width="22" height="22" fill="none" aria-hidden="true">
      <circle cx="12" cy="8.6" r="3.4" stroke="currentColor" strokeWidth="1.7" />
      <path d="M4.8 19.4c1.1-3.4 4-5.2 7.2-5.2s6.1 1.8 7.2 5.2" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" />
    </svg>
  );
}

/** "Coming soon" pair tile lock (Lobby) — same inline-SVG pattern as everywhere else in this
 *  file, not a platform emoji (direction.md §3: an emoji lock rendered as an indistinct dark
 *  badge on the Android/WebView combo this app was verified on). */
export function LockIcon({ className = "" }: { className?: string }) {
  return (
    <svg className={className} viewBox="0 0 24 24" width="20" height="20" fill="none" aria-hidden="true">
      <rect x="5.5" y="10.5" width="13" height="9" rx="1.6" stroke="currentColor" strokeWidth="1.6" />
      <path d="M8.5 10.5V7.8a3.5 3.5 0 0 1 7 0v2.7" stroke="currentColor" strokeWidth="1.6" />
    </svg>
  );
}

/** The HUD balance's coin glyph (direction.md §3) — fixed gold tokens, not `currentColor`: a coin
 *  is gold everywhere it appears, unlike the outcome icons above which inherit their container's
 *  semantic color. */
export function CoinIcon({ className = "" }: { className?: string }) {
  return (
    <svg className={className} viewBox="0 0 24 24" width="20" height="20" aria-hidden="true">
      <circle cx="12" cy="12" r="9" fill="var(--gold)" stroke="var(--gold-strong)" strokeWidth="1.4" />
      <circle cx="12" cy="12" r="5.6" fill="none" stroke="var(--gold-strong)" strokeWidth="1" opacity="0.55" />
      <path
        d="M9.3 9.2c.4-.9 1.3-1.5 2.5-1.5 1.5 0 2.5.8 2.5 1.9 0 .9-.7 1.4-1.7 1.7l-.9.25c-1.1.3-1.8.9-1.8 1.9 0 1.2 1.1 2 2.5 2 1.2 0 2.2-.5 2.6-1.5"
        stroke="var(--bg)"
        strokeWidth="1.2"
        fill="none"
        strokeLinecap="round"
      />
    </svg>
  );
}

/** Favourite star (lobby card, room header) — outline when off, filled gold when on. The pressed
 *  state is also carried by `aria-pressed` on the button, never by the fill alone. */
export function StarIcon({ filled = false, className = "" }: { filled?: boolean; className?: string }) {
  return (
    <svg className={className} viewBox="0 0 24 24" width="20" height="20" aria-hidden="true">
      <path
        d="M12 3.6l2.6 5.4 5.9.8-4.3 4.1 1.1 5.9L12 17l-5.3 2.8 1.1-5.9L3.5 9.8l5.9-.8L12 3.6Z"
        fill={filled ? "var(--gold)" : "none"}
        stroke={filled ? "var(--gold-strong)" : "currentColor"}
        strokeWidth="1.6"
        strokeLinejoin="round"
      />
    </svg>
  );
}

export function SearchIcon({ className = "" }: { className?: string }) {
  return (
    <svg className={className} viewBox="0 0 24 24" width="18" height="18" fill="none" aria-hidden="true">
      <circle cx="10.5" cy="10.5" r="6" stroke="currentColor" strokeWidth="1.8" />
      <path d="m15.2 15.2 4.6 4.6" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
    </svg>
  );
}

/** Flame — the "Hot" chip, the HOT tag and the win-streak badge. */
export function FlameIcon({ className = "" }: { className?: string }) {
  return (
    <svg className={className} viewBox="0 0 24 24" width="18" height="18" fill="none" aria-hidden="true">
      <path
        d="M12.4 3.2c.4 2.6-.9 3.9-2.2 5.3C8.8 10 7.5 11.4 7.5 13.7a4.6 4.6 0 0 0 9.2.2c0-1.5-.7-2.6-1.4-3.4-.2 1-.7 1.7-1.5 2 .5-3.2-.3-6.4-1.4-9.3Z"
        fill="currentColor"
        stroke="currentColor"
        strokeWidth="1"
        strokeLinejoin="round"
      />
    </svg>
  );
}

export function ClockIcon({ className = "" }: { className?: string }) {
  return (
    <svg className={className} viewBox="0 0 24 24" width="18" height="18" fill="none" aria-hidden="true">
      <circle cx="12" cy="12" r="8.2" stroke="currentColor" strokeWidth="1.7" />
      <path d="M12 7.6V12l3 1.8" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}

export function ChevronIcon({ open = false, className = "" }: { open?: boolean; className?: string }) {
  return (
    <svg
      className={className}
      viewBox="0 0 24 24"
      width="18"
      height="18"
      fill="none"
      aria-hidden="true"
      style={{ transform: open ? "rotate(180deg)" : undefined, transition: "transform var(--dur-fast) ease" }}
    >
      <path d="m6.5 9.5 5.5 5.5 5.5-5.5" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}

export function CloseIcon({ className = "" }: { className?: string }) {
  return (
    <svg className={className} viewBox="0 0 24 24" width="16" height="16" fill="none" aria-hidden="true">
      <path d="m6.5 6.5 11 11m0-11-11 11" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" />
    </svg>
  );
}

/** Back arrow of the room header. */
export function ArrowLeftIcon({ className = "" }: { className?: string }) {
  return (
    <svg className={className} viewBox="0 0 24 24" width="22" height="22" fill="none" aria-hidden="true">
      <path d="M19 12H5.5m6-6.5L5.5 12l6 6.5" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}

/** A chain link — "anchored on Chia" (round page). */
export function AnchorIcon({ className = "" }: { className?: string }) {
  return (
    <svg className={className} viewBox="0 0 24 24" width="20" height="20" fill="none" aria-hidden="true">
      <path d="M10.2 13.8a3.6 3.6 0 0 0 5.1 0l3-3a3.6 3.6 0 0 0-5.1-5.1l-1 1" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
      <path d="M13.8 10.2a3.6 3.6 0 0 0-5.1 0l-3 3a3.6 3.6 0 0 0 5.1 5.1l1-1" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
    </svg>
  );
}

/** A speech bubble with two lines of text — the feedback strip (`FeedbackStrip.tsx`, Р-81). */
export function FeedbackIcon({ className = "" }: { className?: string }) {
  return (
    <svg className={className} viewBox="0 0 24 24" width="20" height="20" fill="none" aria-hidden="true">
      <path
        d="M6 4.5h12A2.5 2.5 0 0 1 20.5 7v7.5A2.5 2.5 0 0 1 18 17h-6.2l-4.3 3.5V17H6a2.5 2.5 0 0 1-2.5-2.5V7A2.5 2.5 0 0 1 6 4.5Z"
        stroke="currentColor"
        strokeWidth="1.7"
        strokeLinejoin="round"
      />
      <path d="M8 9.2h8M8 12.4h5" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" />
    </svg>
  );
}

/** A check mark — "sent" in the feedback form. */
export function CheckIcon({ className = "" }: { className?: string }) {
  return (
    <svg className={className} viewBox="0 0 24 24" width="28" height="28" fill="none" aria-hidden="true">
      <path d="m5.5 12.8 4.2 4.2 8.8-9.5" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}

/** "Share": an arrow leaving a tray. */
export function ShareIcon({ className = "" }: { className?: string }) {
  return (
    <svg className={className} viewBox="0 0 24 24" width="16" height="16" fill="none" aria-hidden="true">
      <path d="M12 15V4m0 0L8 8m4-4 4 4" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M5 12v6.5A1.5 1.5 0 0 0 6.5 20h11a1.5 1.5 0 0 0 1.5-1.5V12" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
    </svg>
  );
}
