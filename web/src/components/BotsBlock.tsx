import { useCallback, useEffect, useState, type FormEvent } from "react";
import { Link } from "react-router-dom";
import { useApi } from "../api/ApiContext";
import { mutationErrorText } from "../api/apiErrors";
import { MAX_API_BOTS, type ApiBotDto, type ApiBotKeyDto } from "../api/contract";
import { useT } from "../i18n";
import { CopyButton } from "./Common";
import "./BotsBlock.css";

/**
 * Own profile only (Р-106): the person's API bots - create one (a name and "Create bot key"), see them, revoke or regenerate a key.
 * A key is shown once, right after it is made, in a box of its own that stays until the person says they saved it; it is held in this
 * component's state only (never in storage, never in the list) and gone when the box is closed or the page is left. A failed load shows
 * nothing, like the invite block: a server that predates the feature simply has no such section.
 */
export function BotsBlock() {
  const api = useApi();
  const t = useT();
  const [bots, setBots] = useState<ApiBotDto[] | null>(null);
  const [name, setName] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [revealed, setRevealed] = useState<ApiBotKeyDto | null>(null);

  useEffect(() => {
    let live = true;
    Promise.resolve()
      .then(() => api.myBots())
      .then(
        (list) => live && setBots(list),
        () => undefined,
      );
    return () => {
      live = false;
    };
  }, [api]);

  const replaceBot = useCallback((bot: ApiBotDto) => {
    setBots((list) => (list ? list.map((b) => (b.playerId === bot.playerId ? bot : b)) : list));
  }, []);

  const run = useCallback(
    async (action: () => Promise<void>) => {
      setBusy(true);
      setError(null);
      try {
        await action();
      } catch (err) {
        setError(mutationErrorText(err, t("common.somethingWrong"), t));
      } finally {
        setBusy(false);
      }
    },
    [t],
  );

  if (bots === null) return null;

  const atLimit = bots.length >= MAX_API_BOTS;

  const onCreate = (event: FormEvent) => {
    event.preventDefault();
    if (busy || atLimit || name.trim() === "") return;
    void run(async () => {
      const created = await api.createBot(name.trim());
      setBots((list) => [...(list ?? []), created.bot]);
      setRevealed(created);
      setName("");
    });
  };

  const onRegenerate = (bot: ApiBotDto) =>
    void run(async () => {
      const fresh = await api.regenerateBotKey(bot.playerId);
      replaceBot(fresh.bot);
      setRevealed(fresh);
    });

  const onRevoke = (bot: ApiBotDto) =>
    void run(async () => {
      const revoked = await api.revokeBotKey(bot.playerId);
      replaceBot(revoked);
      setRevealed((current) => (current && current.bot.playerId === bot.playerId ? null : current));
    });

  return (
    <section className="bots-block" aria-labelledby="bots-title">
      <h2 id="bots-title">{t("bots.title")}</h2>
      <p className="muted">{t("bots.hint", { count: MAX_API_BOTS })}</p>
      <p className="muted">{t("bots.noWithdraw")}</p>
      <p>
        <Link to="/developers">{t("bots.docsLink")}</Link>
      </p>

      {revealed && (
        <div className="bots-block__reveal" role="status">
          <p>{t("bots.keyShownOnce", { name: revealed.bot.nickname })}</p>
          <div className="bots-block__row">
            <input
              className="bots-block__key"
              type="text"
              readOnly
              value={revealed.apiKey}
              aria-label={t("bots.keyLabel", { name: revealed.bot.nickname })}
              onFocus={(e) => e.currentTarget.select()}
            />
            <CopyButton value={revealed.apiKey} />
          </div>
          <button type="button" className="btn btn--secondary" onClick={() => setRevealed(null)}>
            {t("bots.keySaved")}
          </button>
        </div>
      )}

      {bots.length === 0 ? (
        <p className="muted">{t("bots.none")}</p>
      ) : (
        <ul className="bots-block__list" aria-label={t("bots.listAria")}>
          {bots.map((bot) => (
            <li key={bot.playerId} className="bots-block__item">
              <div className="bots-block__name">
                <Link to={`/profile/${bot.playerId}`}>{bot.nickname}</Link>
                <span className="muted">{bot.keyActive && bot.keyHint ? t("bots.keyHint", { hint: bot.keyHint }) : t("bots.keyRevoked")}</span>
              </div>
              <div className="bots-block__actions">
                <button type="button" className="btn btn--secondary" disabled={busy} title={t("bots.regenerateNote")} onClick={() => onRegenerate(bot)}>
                  {t("bots.regenerate")}
                </button>
                {bot.keyActive && (
                  <button type="button" className="btn btn--secondary" disabled={busy} onClick={() => onRevoke(bot)}>
                    {t("bots.revoke")}
                  </button>
                )}
              </div>
            </li>
          ))}
        </ul>
      )}

      {atLimit ? (
        <p className="muted">{t("bots.limitReached", { count: MAX_API_BOTS })}</p>
      ) : (
        <form className="bots-block__form" onSubmit={onCreate}>
          <label htmlFor="bot-name" className="visually-hidden">
            {t("bots.nameLabel")}
          </label>
          <input
            id="bot-name"
            className="bots-block__name-input"
            type="text"
            value={name}
            maxLength={24}
            placeholder={t("bots.namePlaceholder")}
            onChange={(e) => setName(e.target.value)}
          />
          <button type="submit" className="btn btn--primary" disabled={busy || name.trim() === ""}>
            {busy ? t("bots.creating") : t("bots.create")}
          </button>
        </form>
      )}

      {error && (
        <p className="bots-block__error" role="alert">
          {error}
        </p>
      )}
    </section>
  );
}
