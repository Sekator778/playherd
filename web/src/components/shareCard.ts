/**
 * The share card of a round result (docs/research/features_growth_2026-10-18.md §3 item 2): a picture drawn on the client and a link to the
 * round page. Pure parts only - the model (what the card says), the drawing (onto a 2D context handed in) and the sharing steps (the browser's
 * share sheet when it takes the picture, otherwise a download and a copied link) - so each is tested without a browser.
 *
 * Rules the card keeps: a loss is shared as easily as a win and is worded as plainly (Р-51, Р-90 is about OTHER players' pages: this is the
 * player's own result, sent by the player); the money is PLAY; no prizes, income or prices (Р-89, Р-103); nothing is sent anywhere by the app
 * itself - the player presses "Share" and picks the place. No server-side og:image (decision D-11).
 */
import type { BetMode, Direction } from "../api/contract";
import { formatPercent, formatPlayAmount } from "../format";
import type { TFn } from "../i18n";
import { isFixedMode } from "./betModeModel";

/** What the card needs to know of a settled bet of the player's own. */
export interface ShareResultInput {
  roundId: string;
  symbol: string;
  /** "3 MIN" and the like, when known. */
  seriesLabel?: string;
  direction: Direction;
  mode?: BetMode | null;
  leverage: number;
  stake: number;
  /** `payout - stake`, signed (never the fees: the number every other screen shows). */
  net: number;
  liquidated: boolean;
}

export type ShareOutcome = "won" | "lost" | "even";

export interface ShareCardModel {
  /** "BTC · 3 MIN". */
  title: string;
  /** "UP" / "DOWN". */
  direction: Direction;
  /** "Win or lose" or "Percent · x10". */
  modeLabel: string;
  outcome: ShareOutcome;
  /** "+12.5 PLAY", "−12.5 PLAY" or "Tie — stake back". */
  resultText: string;
  /** The return on the stake for a percentage bet ("+8.4%", "−25.0%"); null for "Win or lose" and for a tie. */
  percentText: string | null;
  /** "LIQ" for a liquidated percentage bet, else null. */
  liquidatedTag: string | null;
  /** "Recorded on Chia testnet11". */
  chainLine: string;
  /** "playherd.app". */
  address: string;
  /** The line that goes with the picture and the link. */
  shareText: string;
  /** The picture's description for assistive technology. */
  alt: string;
}

export const SHARE_ADDRESS = "playherd.app";
export const SHARE_FILE_NAME = "herd-result.png";

/** "-12.5" -> "−12.5": a real minus sign in a picture. */
function typographic(amount: string): string {
  return amount.startsWith("-") ? `−${amount.slice(1)}` : amount;
}

export function buildShareCard(input: ShareResultInput, t: TFn): ShareCardModel {
  const fixed = isFixedMode({ mode: input.mode });
  const outcome: ShareOutcome = input.net > 0 ? "won" : input.net < 0 ? "lost" : "even";
  const resultText =
    outcome === "even" ? t("mode.tie") : `${typographic(formatPlayAmount(input.net, { sign: true }))} PLAY`;
  const percentText =
    !fixed && outcome !== "even" && input.stake > 0
      ? typographic(formatPercent(input.net / input.stake, { sign: true }))
      : null;
  const modeLabel = fixed ? t("mode.winOrLose") : t("share.modePercent", { leverage: input.leverage });
  const title = input.seriesLabel ? `${input.symbol} · ${input.seriesLabel}` : input.symbol;
  const chainLine = t("share.recorded");
  const shareText = t("share.text", {
    symbol: input.symbol,
    direction: input.direction,
    result: percentText ? `${resultText} (${percentText})` : resultText,
  });
  return {
    title,
    direction: input.direction,
    modeLabel,
    outcome,
    resultText,
    percentText,
    liquidatedTag: input.liquidated && !fixed ? t("resultTile.liq") : null,
    chainLine,
    address: SHARE_ADDRESS,
    shareText,
    alt: `${title} · ${input.direction} · ${modeLabel} · ${resultText}`,
  };
}

/** The link the card carries: the round page with the player's own invite code and the channel `share` (`?ref=<code>&src=share`). Without a
 *  code (not known yet, or the call failed) the link still says where it came from. */
export function shareLink(roundId: string, inviteCode: string | null | undefined, origin: string = window.location.origin): string {
  const query = [...(inviteCode ? [`ref=${encodeURIComponent(inviteCode)}`] : []), "src=share"].join("&");
  return `${origin}/round/${encodeURIComponent(roundId)}?${query}`;
}

// ---------------------------------------------------------------------------
// Drawing
// ---------------------------------------------------------------------------

export const CARD_WIDTH = 1200;
export const CARD_HEIGHT = 630;

/** The slice of CanvasRenderingContext2D the drawing uses - a recording fake satisfies it in tests. */
export interface Card2D {
  fillStyle: string | CanvasGradient | CanvasPattern;
  strokeStyle: string | CanvasGradient | CanvasPattern;
  lineWidth: number;
  font: string;
  textAlign: CanvasTextAlign;
  textBaseline: CanvasTextBaseline;
  fillRect(x: number, y: number, w: number, h: number): void;
  strokeRect(x: number, y: number, w: number, h: number): void;
  fillText(text: string, x: number, y: number, maxWidth?: number): void;
  measureText(text: string): { width: number };
}

const COLORS = {
  background: "#0b0e14",
  panel: "#141a26",
  text: "#f3f5fa",
  muted: "#a7b0c4",
  up: "#22c55e",
  down: "#f87171",
  accent: "#9147ff",
};
const FONT = "system-ui, -apple-system, 'Segoe UI', Roboto, sans-serif";
const MONO = "ui-monospace, SFMono-Regular, Menlo, Consolas, monospace";

const OUTCOME_COLOR: Record<ShareOutcome, string> = { won: COLORS.up, lost: COLORS.down, even: COLORS.muted };

/** Draws the card onto a CARD_WIDTH x CARD_HEIGHT surface. Every text goes through `fillText`, so a test reads what the picture says. */
export function drawShareCard(ctx: Card2D, card: ShareCardModel): void {
  ctx.fillStyle = COLORS.background;
  ctx.fillRect(0, 0, CARD_WIDTH, CARD_HEIGHT);
  ctx.fillStyle = COLORS.panel;
  ctx.fillRect(40, 40, CARD_WIDTH - 80, CARD_HEIGHT - 80);
  ctx.fillStyle = OUTCOME_COLOR[card.outcome];
  ctx.fillRect(40, 40, 12, CARD_HEIGHT - 80);

  ctx.textBaseline = "alphabetic";
  ctx.textAlign = "left";

  // brand and market
  ctx.fillStyle = COLORS.accent;
  ctx.font = `700 40px ${FONT}`;
  ctx.fillText("Herd", 96, 124);
  ctx.fillStyle = COLORS.text;
  ctx.font = `700 64px ${FONT}`;
  ctx.fillText(card.title, 96, 224, CARD_WIDTH - 192);

  // side and mode
  ctx.fillStyle = card.direction === "UP" ? COLORS.up : COLORS.down;
  ctx.font = `700 44px ${FONT}`;
  const side = card.direction;
  ctx.fillText(side, 96, 296);
  const sideWidth = ctx.measureText(side).width;
  ctx.fillStyle = COLORS.muted;
  ctx.font = `500 40px ${FONT}`;
  ctx.fillText(`· ${card.modeLabel}`, 96 + sideWidth + 20, 296, CARD_WIDTH - 192 - sideWidth - 20);

  // the result
  ctx.fillStyle = OUTCOME_COLOR[card.outcome];
  ctx.font = `800 96px ${MONO}`;
  ctx.fillText(card.resultText, 96, 424, CARD_WIDTH - 192);
  const tail = [card.percentText, card.liquidatedTag].filter((x): x is string => x !== null).join(" · ");
  if (tail) {
    ctx.fillStyle = COLORS.muted;
    ctx.font = `600 44px ${MONO}`;
    ctx.fillText(tail, 96, 486, CARD_WIDTH - 192);
  }

  // footer
  ctx.fillStyle = COLORS.muted;
  ctx.font = `500 30px ${FONT}`;
  ctx.fillText(card.chainLine, 96, 556, CARD_WIDTH - 192 - 280);
  ctx.textAlign = "right";
  ctx.fillStyle = COLORS.text;
  ctx.font = `700 34px ${FONT}`;
  ctx.fillText(card.address, CARD_WIDTH - 96, 556);
  ctx.textAlign = "left";
}

/** Renders the card to a PNG. Rejects when the browser has no canvas (or cannot encode): the caller then shares the link alone. */
export async function renderShareImage(card: ShareCardModel, doc: Document = document): Promise<Blob> {
  const canvas = doc.createElement("canvas");
  canvas.width = CARD_WIDTH;
  canvas.height = CARD_HEIGHT;
  const ctx = canvas.getContext("2d");
  if (!ctx) throw new Error("no canvas");
  drawShareCard(ctx, card);
  return await new Promise<Blob>((resolve, reject) => {
    canvas.toBlob((blob) => (blob ? resolve(blob) : reject(new Error("no image"))), "image/png");
  });
}

// ---------------------------------------------------------------------------
// Sharing
// ---------------------------------------------------------------------------

/** How a press of "Share" ended: `shared` - the browser's share sheet took it; `saved` / `savedAndCopied` - the picture was downloaded (and the link copied); `copied` - only the link was copied; `cancelled` - the player closed the share sheet; `failed` - nothing could be done. */
export type ShareEnd = "shared" | "saved" | "savedAndCopied" | "copied" | "cancelled" | "failed";

/** The browser pieces the sharing uses, injectable for tests. */
export interface ShareEnv {
  nav: Pick<Navigator, "share" | "canShare" | "clipboard"> | Record<string, unknown>;
  /** Downloads the file under its name; returns whether it could start. */
  download(blob: Blob, fileName: string): boolean;
}

interface ShareNav {
  share?: (data: ShareData) => Promise<void>;
  canShare?: (data: ShareData) => boolean;
  clipboard?: { writeText(text: string): Promise<void> };
}

export function browserShareEnv(): ShareEnv {
  return {
    nav: typeof navigator === "undefined" ? {} : navigator,
    download(blob, fileName) {
      try {
        const url = URL.createObjectURL(blob);
        const a = document.createElement("a");
        a.href = url;
        a.download = fileName;
        document.body.appendChild(a);
        a.click();
        a.remove();
        window.setTimeout(() => URL.revokeObjectURL(url), 10_000);
        return true;
      } catch {
        return false;
      }
    },
  };
}

/**
 * Shares the picture and the link. With the Web Share API that takes files, the picture goes with the text and the link; otherwise the picture is
 * downloaded and the link copied. A player who closes the share sheet is not told anything went wrong.
 */
export async function performShare(
  parts: { image: Blob | null; link: string; text: string; title: string },
  env: ShareEnv = browserShareEnv(),
): Promise<ShareEnd> {
  const nav = env.nav as ShareNav;
  if (typeof nav.share === "function") {
    const file = parts.image ? new File([parts.image], SHARE_FILE_NAME, { type: "image/png" }) : null;
    const withFile: ShareData | null = file ? { files: [file], title: parts.title, text: parts.text, url: parts.link } : null;
    const linkOnly: ShareData = { title: parts.title, text: parts.text, url: parts.link };
    try {
      if (withFile && typeof nav.canShare === "function" && nav.canShare(withFile)) {
        await nav.share(withFile);
        return "shared";
      }
      // a browser that shares text and links but not files: the picture is saved, the link goes to the share sheet
      if (parts.image) env.download(parts.image, SHARE_FILE_NAME);
      await nav.share(linkOnly);
      return "shared";
    } catch (err) {
      if ((err as { name?: string } | null)?.name === "AbortError") return "cancelled";
      // fall through to the download and the copied link
    }
  }
  const saved = parts.image ? env.download(parts.image, SHARE_FILE_NAME) : false;
  const copied = await Promise.resolve()
    .then(() => nav.clipboard?.writeText(`${parts.text} ${parts.link}`))
    .then(() => nav.clipboard !== undefined)
    .catch(() => false);
  if (saved) return copied ? "savedAndCopied" : "saved";
  return copied ? "copied" : "failed";
}
