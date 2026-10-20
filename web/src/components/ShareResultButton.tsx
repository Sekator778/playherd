import { useEffect, useRef, useState } from "react";
import { useApi } from "../api/ApiContext";
import type { Api } from "../api/contract";
import { useT } from "../i18n";
import { ShareIcon } from "./icons";
import {
  buildShareCard,
  performShare,
  renderShareImage,
  shareLink,
  type ShareEnd,
  type ShareEnv,
  type ShareResultInput,
} from "./shareCard";
import "./ShareResultButton.css";

/** The player's own invite code, asked once per `Api` instance: every button on a page uses the one answer. */
const codeByApi = new WeakMap<Api, Promise<string | null>>();

function inviteCodeOf(api: Api): Promise<string | null> {
  let known = codeByApi.get(api);
  if (!known) {
    known = Promise.resolve()
      .then(() => api.myInvite())
      .then(
        (invite) => invite.code,
        () => null, // not signed in, or an older server: the link then carries the channel only
      );
    codeByApi.set(api, known);
    // a failed answer is not kept: the next button asks again
    void known.then((code) => {
      if (code === null && codeByApi.get(api) === known) codeByApi.delete(api);
    });
  }
  return known;
}

const NOTE_KEY: Record<Exclude<ShareEnd, "cancelled">, "share.shared" | "share.saved" | "share.savedAndCopied" | "share.copied" | "share.failed"> = {
  shared: "share.shared",
  saved: "share.saved",
  savedAndCopied: "share.savedAndCopied",
  copied: "share.copied",
  failed: "share.failed",
};

/**
 * "Share" on a settled result of the player's own: draws the card on the client, then hands the picture and the link to the browser's share
 * sheet, or downloads the picture and copies the link. A loss has the same button as a win (Р-51). Nothing happens until the player presses it.
 */
export function ShareResultButton({
  result,
  onBusy,
  className = "",
  env,
}: {
  result: ShareResultInput;
  /** Called when a press starts: the result card uses it to stop its own auto-dismiss while the share sheet is open. */
  onBusy?: () => void;
  className?: string;
  /** Test seam: the browser pieces the sharing uses. */
  env?: ShareEnv;
}) {
  const api = useApi();
  const t = useT();
  const [code, setCode] = useState<string | null>(null);
  const [note, setNote] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const live = useRef(true);

  useEffect(() => {
    live.current = true;
    inviteCodeOf(api).then((c) => live.current && setCode(c));
    return () => {
      live.current = false;
    };
  }, [api]);

  const press = async (event: React.MouseEvent) => {
    // inside a card that dismisses on a tap, and next to links: this press is the button's alone
    event.stopPropagation();
    event.preventDefault();
    if (busy) return;
    onBusy?.();
    setBusy(true);
    setNote(null);
    const card = buildShareCard(result, t);
    const link = shareLink(result.roundId, code ?? (await inviteCodeOf(api)));
    // no canvas: the link alone goes out
    const image = await renderShareImage(card).catch(() => null);
    const end = await performShare({ image, link, text: card.shareText, title: "Herd" }, env);
    if (!live.current) return;
    setBusy(false);
    setNote(end === "cancelled" ? null : t(NOTE_KEY[end]));
  };

  return (
    <span className={`share-result ${className}`.trim()}>
      <button type="button" className="share-result__btn" onClick={press} disabled={busy} aria-label={t("share.aria")}>
        <ShareIcon className="share-result__icon" />
        {t("share.button")}
      </button>
      <span className="share-result__note faint" role="status" aria-live="polite">
        {note}
      </span>
    </span>
  );
}
