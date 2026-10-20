package crowdvsmarket.server.snapshot;

import crowdvsmarket.engine.crowd.CrowdAccount;
import crowdvsmarket.engine.economy.Fees;
import crowdvsmarket.engine.ledger.DepositRequest;
import crowdvsmarket.engine.ledger.DepositState;
import crowdvsmarket.engine.ledger.LedgerBook;
import crowdvsmarket.engine.ledger.LedgerCorrection;
import crowdvsmarket.engine.ledger.PlayAmount;
import crowdvsmarket.engine.ledger.PlayerAccount;
import crowdvsmarket.engine.ledger.SeasonRecord;
import crowdvsmarket.engine.ledger.WithdrawalRequest;
import crowdvsmarket.engine.ledger.WithdrawalState;
import crowdvsmarket.engine.margin.Leverage;
import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.Bet;
import crowdvsmarket.engine.round.BetBook;
import crowdvsmarket.engine.round.BetMode;
import crowdvsmarket.engine.round.Direction;
import crowdvsmarket.engine.round.LiquidationParams;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.engine.season.Season;
import crowdvsmarket.server.domain.BetFeed;
import crowdvsmarket.server.domain.BetRequests;
import crowdvsmarket.server.domain.ComparisonAccounts;
import crowdvsmarket.server.domain.EngineHub;
import crowdvsmarket.server.domain.PlayerRoster;
import crowdvsmarket.server.dto.Enums;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The engine state as text, and back (architecture plan F5): everything a replay of the journal builds - the ledger (accounts with
 * their seasons, withdrawal and deposit requests, corrections, the house accounts and counters), per market the crowd account, the
 * comparison lines and the bet books of both series, the index of the windows that hold bets, the memory of the closed rounds, the
 * player roster, the oracle sequence number, the bet feed and the memory of the request ids of the bets ({@code betRequest} lines: a
 * state without such lines - every snapshot taken before the ids existed - restores an empty memory, which is what a replay of a
 * journal without ids builds too, so {@link #FORMAT_VERSION} stays).
 *
 * <p>The text is canonical: one fact per line, tab separated, strings escaped ({@code \t \n \r \\}, {@code \N} for null), markets and
 * the roster in name order, every other collection in the order the engine keeps it (which a replay reproduces). It is both the
 * body of a snapshot (compressed by {@link SnapshotService}) and what {@link StateDigest} hashes, so a digest covers exactly what the
 * codec carries. The aggregates of a bet book (F2) and the totals of the ledger are not derived anew: the book's own
 * {@code place} recomputes the former, the latter are carried as they were.
 *
 * <p>{@link #FORMAT_VERSION} changes whenever a line changes its meaning; a body of another version is not decoded.
 */
public final class StateCodec {

    /** The version of the text format; stored beside every snapshot. */
    public static final int FORMAT_VERSION = 1;

    private static final String NULL = "\\N";

    private StateCodec() {
    }

    // ---------------------------------------------------------------------------------------------------------------
    // encode
    // ---------------------------------------------------------------------------------------------------------------

    /** The canonical text of the state in {@code hub} and {@code feed}. The caller keeps the state still meanwhile. */
    public static String encode(EngineHub hub, BetFeed feed) {
        Out out = new Out();
        out.line("format", FORMAT_VERSION);
        out.line("oracleSeq", hub.oracleSeq());
        encodeLedger(out, hub.ledger().exportState());
        hub.roster().all().stream().sorted(Comparator.comparing(PlayerRoster.Entry::playerId)).forEach(e ->
            out.line("roster", e.playerId(), e.walletAddress(), e.nickname(), e.kind(), e.botStrategy(), e.ownerPlayerId()));
        List<String> markets = new ArrayList<>(hub.marketIds());
        markets.sort(Comparator.naturalOrder());
        for (String market : markets) {
            EngineHub.MarketEngines engines = hub.market(market);
            out.line("market", market);
            CrowdAccount crowd = engines.crowd();
            out.line("crowd", market, crowd.capital());
            for (CrowdAccount.RoundResult r : crowd.history()) {
                out.line("crowdRound", market, r.roundId(), r.traded(), r.direction(), r.leverage(), r.margin(), r.entryPrice(),
                        r.liquidated(), r.capitalBefore(), r.capitalAfter());
            }
            ComparisonAccounts.State comparison = engines.comparison().state();
            out.line("comparison", market, comparison.holdCapital(), comparison.holdQty(), comparison.antiCrowdCapital());
            comparison.holdHistory().forEach(v -> out.line("hold", market, v));
            comparison.antiCrowdHistory().forEach(v -> out.line("anti", market, v));
            for (Interval interval : Interval.values()) {
                BetBook book = engines.engine(interval).betBook();
                for (String roundId : book.roundIdsWithEntries()) {
                    for (Bet bet : book.betsFor(roundId)) {
                        out.line("bet", market, interval, bet.roundId(), bet.playerId(), bet.direction(), bet.leverage().value(),
                                bet.stake().mojos(), bet.fees() == null ? null : bet.fees().herd().mojos(),
                                bet.fees() == null ? null : bet.fees().network().mojos(),
                                bet.liquidation() == null ? null : bet.liquidation().cap(),
                                bet.liquidation() == null ? null : bet.liquidation().kappa(), bet.mode());
                    }
                }
                for (RoundWindow window : hub.betWindows().of(market, interval)) {
                    out.line("window", market, interval, window.startMs(), window.endMs());
                }
                for (long[] closed : hub.closedRounds().remembered(market, interval)) {
                    out.line("closed", market, interval, closed[0], closed[1]);
                }
                hub.closedRounds().latestEnd(market, interval).ifPresent(end -> out.line("latestEnd", market, interval, end));
            }
        }
        for (BetRequests.Entry r : hub.betRequests().all()) {
            out.line("betRequest", r.playerId(), r.requestId(), r.atMs(), r.roundId(), r.direction(), r.leverage(), r.stakeMojos(),
                    r.feeHerdMojos(), r.feeNetMojos(), r.fixed());
        }
        BetFeed.State fed = feed.state();
        out.line("feedSeq", fed.seq());
        fed.rounds().forEach((roundId, entries) -> {
            out.line("feedRound", roundId);
            for (BetFeed.Entry e : entries) {
                out.line("feedEntry", roundId, e.seq(), e.atMs(), e.playerId(), e.playerDisplayName(), e.isBot(), e.direction(),
                        e.stake(), e.leverage(), e.mode());
            }
        });
        out.line("end", out.count);
        return out.toString();
    }

    private static void encodeLedger(Out out, LedgerBook.State s) {
        out.line("ledger", s.sequence(), s.requestCounter(), s.correctionCounter(), s.economyActive(), s.treasury().mojos(),
                s.herd().mojos(), s.openStakes().mojos(), s.playersTotal().mojos(), s.openingTotal().mojos(),
                s.externalFlow().mojos(), s.externalFlowAtCutover().mojos(), s.depositedTotal().mojos(),
                s.depositedAtCutover().mojos(), s.withdrawnConfirmed().mojos(), s.cutoverAtMs(), s.botFeesSwept().mojos(),
                s.lastSweepAtMs());
        for (PlayerAccount a : s.accounts()) {
            out.line("account", a.playerId(), a.balance().mojos(), a.currentSeason().id());
            for (SeasonRecord r : a.seasons().values()) {
                out.line("season", a.playerId(), r.season().id(), r.baselineBalance().mojos(), r.deposited().mojos(),
                        r.withdrawn().mojos(), r.roundsPlayed(), r.directionEligibleRounds(), r.directionCorrectRounds(),
                        r.starterPack().mojos());
            }
        }
        for (WithdrawalRequest w : s.withdrawals()) {
            out.line("withdrawal", w.requestId(), w.playerId(), w.amount().mojos(), w.state(), w.requestedAtEpochMs());
        }
        for (DepositRequest d : s.deposits()) {
            out.line("deposit", d.requestId(), d.playerId(), d.amount().mojos(), d.state(), d.requestedAtEpochMs());
        }
        for (LedgerCorrection c : s.corrections()) {
            out.line("correction", c.correctionId(), c.playerId(), c.delta().mojos(), c.reason(), c.relatedRequestId(),
                    c.atEpochMs());
        }
        s.packCredited().forEach((player, amount) -> out.line("pack", player, amount.mojos()));
        s.feesPaid().forEach((player, amount) -> out.line("feesPaid", player, amount.mojos()));
        s.retired().forEach(player -> out.line("retired", player));
        s.treasuryDayMax().forEach((day, amount) -> out.line("treasuryDay", day, amount.mojos()));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // decode
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Puts the state {@code text} describes into {@code hub} and {@code feed}, both fresh; every market of the text must have its
     * engines in {@code hub} already (a snapshot of a market the registry no longer has is refused) and the roster entries
     * replace the hub's.
     *
     * @throws IllegalArgumentException the text is not a state of this format, or does not fit the hub
     */
    public static void decode(String text, EngineHub hub, BetFeed feed) {
        try {
            decodeLines(text, hub, feed);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("the state text cannot be decoded: " + e, e);
        }
    }

    private static void decodeLines(String text, EngineHub hub, BetFeed feed) {
        String[] lines = text.split("\n", -1);
        if (lines.length < 2 || !lines[lines.length - 1].isEmpty()) {
            throw new IllegalArgumentException("the state text does not end with a newline");
        }
        LedgerAcc ledger = new LedgerAcc();
        Map<String, List<CrowdAccount.RoundResult>> crowdHistory = new LinkedHashMap<>();
        Map<String, BigDecimal> crowdCapital = new LinkedHashMap<>();
        Map<String, BigDecimal[]> comparison = new LinkedHashMap<>();
        Map<String, List<BigDecimal>> hold = new LinkedHashMap<>();
        Map<String, List<BigDecimal>> anti = new LinkedHashMap<>();
        Map<String, Map<Interval, List<long[]>>> closed = new LinkedHashMap<>();
        Map<String, Map<Interval, Long>> newest = new LinkedHashMap<>();
        Map<String, List<BetFeed.Entry>> feedRounds = new LinkedHashMap<>();
        List<BetRequests.Entry> betRequests = new ArrayList<>();
        long feedSeq = 0;
        String feedRound = null;
        long count = 0;
        boolean ended = false;
        boolean first = true;
        for (int n = 0; n < lines.length - 1; n++) {
            String[] f = split(lines[n]);
            String kind = f[0];
            if (first) {
                if (!kind.equals("format") || Integer.parseInt(f[1]) != FORMAT_VERSION) {
                    throw new IllegalArgumentException("not a state of format " + FORMAT_VERSION + ": " + lines[n]);
                }
                first = false;
                count = 1;
                continue;
            }
            if (ended) {
                throw new IllegalArgumentException("lines after the end line");
            }
            switch (kind) {
                case "end" -> {
                    if (Long.parseLong(f[1]) != count) {
                        throw new IllegalArgumentException("the end line says " + f[1] + " lines, there are " + count);
                    }
                    ended = true;
                    continue; // not counted
                }
                case "oracleSeq" -> hub.raiseOracleSeq(Long.parseLong(f[1]));
                case "ledger" -> ledger.core(f);
                case "account" -> ledger.account(f);
                case "season" -> ledger.season(f);
                case "withdrawal" -> ledger.withdrawals.add(new WithdrawalRequest(f[1], f[2], amount(f[3]),
                        WithdrawalState.valueOf(f[4]), Long.parseLong(f[5])));
                case "deposit" -> ledger.deposits.add(new DepositRequest(f[1], f[2], amount(f[3]), DepositState.valueOf(f[4]),
                        Long.parseLong(f[5])));
                case "correction" -> ledger.corrections.add(new LedgerCorrection(f[1], f[2], amount(f[3]), f[4], f[5],
                        Long.parseLong(f[6])));
                case "pack" -> ledger.packCredited.put(f[1], amount(f[2]));
                case "feesPaid" -> ledger.feesPaid.put(f[1], amount(f[2]));
                case "retired" -> ledger.retired.add(f[1]);
                case "treasuryDay" -> ledger.treasuryDayMax.put(Long.parseLong(f[1]), amount(f[2]));
                case "roster" -> hub.roster().register(new PlayerRoster.Entry(f[1], f[2], f[3], f[4], f[5], f[6]));
                case "market" -> hub.findMarket(f[1]).orElseThrow(() -> new IllegalArgumentException(
                        "the state has market " + f[1] + " that the registry does not"));
                case "crowd" -> crowdCapital.put(f[1], decimal(f[2]));
                case "crowdRound" -> crowdHistory.computeIfAbsent(f[1], m -> new ArrayList<>()).add(
                        new CrowdAccount.RoundResult(f[2], Boolean.parseBoolean(f[3]), f[4] == null ? null : Direction.valueOf(f[4]),
                                f[5] == null ? null : Integer.valueOf(f[5]), decimal(f[6]), decimal(f[7]),
                                Boolean.parseBoolean(f[8]), decimal(f[9]), decimal(f[10])));
                case "comparison" -> comparison.put(f[1], new BigDecimal[] {decimal(f[2]), decimal(f[3]), decimal(f[4])});
                case "hold" -> hold.computeIfAbsent(f[1], m -> new ArrayList<>()).add(decimal(f[2]));
                case "anti" -> anti.computeIfAbsent(f[1], m -> new ArrayList<>()).add(decimal(f[2]));
                case "bet" -> {
                    EngineHub.MarketEngines engines = hub.findMarket(f[1]).orElseThrow(() -> new IllegalArgumentException(
                            "a bet on market " + f[1] + " that has no engines"));
                    Fees fees = f[8] == null ? null : new Fees(amount(f[8]), amount(f[9]));
                    LiquidationParams liquidation = f[10] == null ? null : new LiquidationParams(Integer.parseInt(f[10]),
                            new BigDecimal(f[11]));
                    engines.engine(Interval.valueOf(f[2])).betBook().place(new Bet(f[4], f[3], Direction.valueOf(f[5]),
                            new Leverage(Integer.parseInt(f[6])), amount(f[7]), fees, liquidation, BetMode.valueOf(f[12])));
                }
                case "window" -> hub.betWindows().placed(new RoundWindow(f[1], Interval.valueOf(f[2]), Long.parseLong(f[3]),
                        Long.parseLong(f[4])));
                case "closed" -> closed.computeIfAbsent(f[1], m -> new LinkedHashMap<>())
                        .computeIfAbsent(Interval.valueOf(f[2]), i -> new ArrayList<>())
                        .add(new long[] {Long.parseLong(f[3]), Long.parseLong(f[4])});
                case "latestEnd" -> newest.computeIfAbsent(f[1], m -> new LinkedHashMap<>())
                        .put(Interval.valueOf(f[2]), Long.parseLong(f[3]));
                case "betRequest" -> betRequests.add(new BetRequests.Entry(f[1], f[2], Long.parseLong(f[3]), f[4],
                        Direction.valueOf(f[5]), Integer.parseInt(f[6]), Long.parseLong(f[7]),
                        f[8] == null ? null : Long.parseLong(f[8]), f[9] == null ? null : Long.parseLong(f[9]),
                        Boolean.parseBoolean(f[10])));
                case "feedSeq" -> feedSeq = Long.parseLong(f[1]);
                case "feedRound" -> {
                    feedRound = f[1];
                    feedRounds.put(feedRound, new ArrayList<>());
                }
                case "feedEntry" -> {
                    if (!f[1].equals(feedRound)) {
                        throw new IllegalArgumentException("a feed entry outside its round: " + lines[n]);
                    }
                    feedRounds.get(feedRound).add(new BetFeed.Entry(Long.parseLong(f[2]), Long.parseLong(f[3]), f[4], f[5],
                            Boolean.parseBoolean(f[6]), Enums.Direction.valueOf(f[7]), f[8], Integer.parseInt(f[9]),
                            f[10] == null ? null : Enums.BetMode.valueOf(f[10])));
                }
                default -> throw new IllegalArgumentException("unknown line: " + lines[n]);
            }
            count++;
        }
        if (!ended) {
            throw new IllegalArgumentException("the state text has no end line");
        }
        hub.ledger().restoreState(ledger.build());
        for (String market : hub.marketIds()) {
            EngineHub.MarketEngines engines = hub.market(market);
            if (crowdCapital.containsKey(market)) {
                engines.crowd().restore(crowdCapital.get(market), crowdHistory.getOrDefault(market, List.of()));
            }
            BigDecimal[] c = comparison.get(market);
            if (c != null) {
                engines.comparison().restore(new ComparisonAccounts.State(c[0], c[1], c[2],
                        hold.getOrDefault(market, List.of()), anti.getOrDefault(market, List.of())));
            }
            for (Interval interval : Interval.values()) {
                hub.closedRounds().restore(market, interval,
                        closed.getOrDefault(market, Map.of()).getOrDefault(interval, List.of()),
                        newest.getOrDefault(market, Map.of()).get(interval));
            }
        }
        hub.betRequests().restore(betRequests);
        feed.restore(new BetFeed.State(feedSeq, feedRounds));
    }

    /** The lines of a ledger state, collected until the whole state can be built. */
    private static final class LedgerAcc {
        long sequence;
        long requestCounter;
        long correctionCounter;
        boolean economyActive;
        PlayAmount[] amounts = new PlayAmount[11];
        long cutoverAtMs;
        long lastSweepAtMs;
        final List<PlayerAccount> accounts = new ArrayList<>();
        final List<WithdrawalRequest> withdrawals = new ArrayList<>();
        final List<DepositRequest> deposits = new ArrayList<>();
        final List<LedgerCorrection> corrections = new ArrayList<>();
        final Map<String, PlayAmount> packCredited = new LinkedHashMap<>();
        final Map<String, PlayAmount> feesPaid = new LinkedHashMap<>();
        final List<String> retired = new ArrayList<>();
        final Map<Long, PlayAmount> treasuryDayMax = new java.util.TreeMap<>();
        private String accountId;
        private PlayAmount accountBalance;
        private Season accountSeason;
        private Map<Season, SeasonRecord> accountSeasons;

        void core(String[] f) {
            sequence = Long.parseLong(f[1]);
            requestCounter = Long.parseLong(f[2]);
            correctionCounter = Long.parseLong(f[3]);
            economyActive = Boolean.parseBoolean(f[4]);
            // treasury, herd, openStakes, playersTotal, openingTotal, externalFlow, externalFlowAtCutover, depositedTotal,
            // depositedAtCutover, withdrawnConfirmed, [cutoverAtMs], botFeesSwept, [lastSweepAtMs]
            for (int i = 0; i < 10; i++) {
                amounts[i] = amount(f[5 + i]);
            }
            cutoverAtMs = Long.parseLong(f[15]);
            amounts[10] = amount(f[16]);
            lastSweepAtMs = Long.parseLong(f[17]);
        }

        void account(String[] f) {
            closeAccount();
            accountId = f[1];
            accountBalance = amount(f[2]);
            accountSeason = Season.parse(f[3]);
            accountSeasons = new LinkedHashMap<>();
        }

        void season(String[] f) {
            if (accountId == null || !accountId.equals(f[1])) {
                throw new IllegalArgumentException("a season outside its account: " + f[1]);
            }
            Season season = Season.parse(f[2]);
            accountSeasons.put(season, new SeasonRecord(season, amount(f[3]), amount(f[4]), amount(f[5]),
                    Integer.parseInt(f[6]), Integer.parseInt(f[7]), Integer.parseInt(f[8]), amount(f[9])));
        }

        private void closeAccount() {
            if (accountId != null) {
                accounts.add(new PlayerAccount(accountId, accountBalance, accountSeason, accountSeasons));
                accountId = null;
            }
        }

        LedgerBook.State build() {
            closeAccount();
            return new LedgerBook.State(accounts, withdrawals, deposits, corrections, sequence, requestCounter,
                    correctionCounter, economyActive, amounts[0], amounts[1], amounts[2], amounts[3], amounts[4], amounts[5],
                    amounts[6], amounts[7], amounts[8], amounts[9], cutoverAtMs, packCredited, feesPaid, amounts[10],
                    lastSweepAtMs, retired, treasuryDayMax);
        }
    }

    private static PlayAmount amount(String mojos) {
        return new PlayAmount(Long.parseLong(mojos));
    }

    private static BigDecimal decimal(String value) {
        return value == null ? null : new BigDecimal(value);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // lines
    // ---------------------------------------------------------------------------------------------------------------

    private static final class Out {
        private final StringBuilder text = new StringBuilder(1 << 16);
        long count;

        void line(String kind, Object... fields) {
            text.append(kind);
            for (Object field : fields) {
                text.append('\t');
                escape(text, field);
            }
            text.append('\n');
            count++;
        }

        @Override
        public String toString() {
            return text.toString();
        }
    }

    private static void escape(StringBuilder out, Object value) {
        if (value == null) {
            out.append(NULL);
            return;
        }
        String s = value instanceof BigDecimal d ? d.toString() : value.toString();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '\t' -> out.append("\\t");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                default -> out.append(c);
            }
        }
    }

    /** A line split into its kind and fields, the fields unescaped ({@code null} for {@code \N}). */
    private static String[] split(String line) {
        List<String> fields = new ArrayList<>();
        int from = 0;
        while (true) {
            int tab = line.indexOf('\t', from);
            fields.add(unescape(tab < 0 ? line.substring(from) : line.substring(from, tab)));
            if (tab < 0) {
                break;
            }
            from = tab + 1;
        }
        // a short line must fail as a decoding error, not as an index error deep in a case: pad is not done on purpose
        return fields.toArray(new String[0]);
    }

    private static String unescape(String field) {
        if (field.equals(NULL)) {
            return null;
        }
        if (field.indexOf('\\') < 0) {
            return field;
        }
        StringBuilder out = new StringBuilder(field.length());
        for (int i = 0; i < field.length(); i++) {
            char c = field.charAt(i);
            if (c != '\\') {
                out.append(c);
                continue;
            }
            if (++i >= field.length()) {
                throw new IllegalArgumentException("a field ends with a lone backslash");
            }
            switch (field.charAt(i)) {
                case '\\' -> out.append('\\');
                case 't' -> out.append('\t');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                default -> throw new IllegalArgumentException("unknown escape \\" + field.charAt(i));
            }
        }
        return out.toString();
    }
}
