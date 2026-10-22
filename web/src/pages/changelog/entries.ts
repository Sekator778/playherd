import type { TKey } from "../../i18n";

/**
 * "What's new in Herd" (`/changelog`): what a player or a bot developer can see or feel, newest first. `date` is the day the change went
 * live on playherd.app (UTC), taken from `docs/product/feedback/wave1-log.md` (rows of the stand), `ROADMAP.md` and `docs/HANDOFF.md` §0.
 * Every item is an i18n key `changelog.item.*`. A new update = a new entry at the top.
 */
export interface ChangelogEntry {
  /** ISO day, UTC. */
  date: string;
  items: readonly TKey[];
}

export const CHANGELOG: readonly ChangelogEntry[] = [
  { date: "2026-10-21", items: ["changelog.item.fasterServer", "changelog.item.android"] },
  { date: "2026-10-20", items: ["changelog.item.winOrLose", "changelog.item.shareCard", "changelog.item.weekAndStreak", "changelog.item.botApi", "changelog.item.live"] },
  { date: "2026-10-18", items: ["changelog.item.feeSheet", "changelog.item.feesPage", "changelog.item.youMark", "changelog.item.invite"] },
  { date: "2026-10-16", items: ["changelog.item.myBet", "changelog.item.betSheet", "changelog.item.resultLine", "changelog.item.bots", "changelog.item.keepBet"] },
  { date: "2026-10-15", items: ["changelog.item.tokenTexts", "changelog.item.economy"] },
  { date: "2026-10-14", items: ["changelog.item.sixLangs", "changelog.item.chainCatchUp"] },
  { date: "2026-10-11", items: ["changelog.item.launch", "changelog.item.wallet", "changelog.item.feedback"] },
];
