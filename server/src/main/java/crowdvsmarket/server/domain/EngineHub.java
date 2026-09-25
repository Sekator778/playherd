package crowdvsmarket.server.domain;

import crowdvsmarket.engine.crowd.CrowdAccount;
import crowdvsmarket.engine.game.GameEngine;
import crowdvsmarket.engine.ledger.LedgerBook;
import crowdvsmarket.engine.market.Interval;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Holds the live, in-memory engine state: one shared {@link LedgerBook} and one shared
 * {@link CrowdAccount} — balances and the crowd's continuous equity curve are common to both
 * round series (Р-30: "оба рейтинга — общие для часовых и дневных раундов"; Р-25: "один
 * непрерывный график") — behind two {@link GameEngine} facades, one per {@link Interval} (each
 * with its own private {@code BetBook}, per round_engine_design.md §1: "construct one GameEngine
 * per series").
 *
 * <p>Every mutating call in this codebase goes through {@code GameService}, which synchronizes on
 * this instance for the whole of one logical operation (persist event(s) + mutate this state +
 * update read models) — none of {@code LedgerBook}/{@code CrowdAccount}/{@code GameEngine} is
 * internally thread-safe (round_engine_design.md §3: "Однопоточный... функциональное ядро").
 */
@ApplicationScoped
public final class EngineHub {

    private volatile LedgerBook ledger;
    private volatile CrowdAccount crowdAccount;
    private volatile GameEngine hourlyEngine;
    private volatile GameEngine dailyEngine;

    public EngineHub() {
        resetEmpty();
    }

    /** Discards all in-memory state and starts a brand-new engine — the first step of a rebuild
     *  ({@code EngineRebuildService}), immediately before replaying {@code game_events}. */
    public void resetEmpty() {
        LedgerBook newLedger = new LedgerBook();
        CrowdAccount newCrowd = new CrowdAccount();
        this.ledger = newLedger;
        this.crowdAccount = newCrowd;
        this.hourlyEngine = new GameEngine(newLedger, newCrowd);
        this.dailyEngine = new GameEngine(newLedger, newCrowd);
    }

    public LedgerBook ledger() {
        return ledger;
    }

    public CrowdAccount crowdAccount() {
        return crowdAccount;
    }

    public GameEngine engine(Interval interval) {
        return interval == Interval.HOURLY ? hourlyEngine : dailyEngine;
    }
}
