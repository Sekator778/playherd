import { useEffect } from "react";
import { useApi } from "../api/ApiContext";
import { useEngineNotReady } from "../api/engineState";
import { useT } from "../i18n";
import "./EngineBanner.css";

/** How often the banner asks the server whether it is back, while it is showing. */
export const ENGINE_RECHECK_MS = 3000;

/**
 * "The server is restarting" — the one small notice for the transient state in which the server answers HTTP 503
 * `ENGINE_NOT_READY` (`contract.ts`): it is rebuilding its state after a failure and has changed nothing. It is a
 * normal state, not a crash: every screen keeps what it shows and loads again by itself (`api/engineState.ts`), a
 * button that needs the server says so (`api/apiErrors.ts`), and this line tells the player why nothing moves.
 *
 * It is in the page flow under the header, never over a screen's own controls, and it asks the server every few
 * seconds whether it is back (`serverInfo()` answers during a rebuild and says `engineReady`; the app's own polls of
 * markets and rounds clear it too, whichever answers first). A build without `serverInfo()` (the mock) never shows it.
 */
export function EngineBanner() {
  const api = useApi();
  const notReady = useEngineNotReady();

  useEffect(() => {
    if (!notReady || !api.serverInfo) return;
    const ask = () => {
      // The answer itself is read by the transport (`realApi.ts` marks the engine ready) — only the asking is here.
      if (document.visibilityState === "visible") api.serverInfo?.().catch(() => {});
    };
    const id = window.setInterval(ask, ENGINE_RECHECK_MS);
    return () => window.clearInterval(id);
  }, [api, notReady]);

  return notReady ? <EngineNotice /> : null;
}

/** The notice itself (also drawn on its own in `/lab/states`). */
export function EngineNotice() {
  const t = useT();
  return (
    <div className="engine-banner" role="status">
      <span className="engine-banner__dot" aria-hidden="true" />
      <p>
        <strong>{t("engine.title")}</strong> {t("engine.body")}
      </p>
    </div>
  );
}
