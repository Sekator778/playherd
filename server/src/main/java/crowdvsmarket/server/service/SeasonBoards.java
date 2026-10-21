package crowdvsmarket.server.service;

import crowdvsmarket.engine.ledger.PlayAmount;
import crowdvsmarket.engine.rating.RatingBook;
import crowdvsmarket.engine.season.Season;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The season leaderboards ("богачи" and "пророки", Р-18) of every season, kept sorted as part of the read views (architecture plan F8):
 * what {@link RatingBook} computes by walking the whole ledger on each call is here a sorted array per season and board, and a
 * batch moves only the rows of the players it touched ({@link #with}: one pass over each array that changes, not a new sort). A
 * value of this class never changes; {@link #with} and {@link #of} build the next one.
 *
 * <p>The rows are the ones {@link RatingBook#richEntry} and {@link RatingBook#prophetEntry} compute, in the order {@link
 * RatingBook#richList} and {@link RatingBook#prophetList} give: by growth (accuracy) descending, a tie in the order the ledger saw the
 * players ({@link crowdvsmarket.engine.ledger.LedgerBook#joinOrder}) - which is what a stable sort over the ledger's player list
 * does. A retired player is in no board. The boards hold the people and the API bots together; the reader tells them apart.
 */
final class SeasonBoards {

    static final SeasonBoards EMPTY = new SeasonBoards(Map.of());

    /** A row of the "богачи" board and where its player joined the ledger (the tie-break). */
    record RichRow(RatingBook.RichEntry entry, int joinOrder) {
    }

    /** A row of the "пророки" board and where its player joined the ledger (the tie-break). */
    record ProphetRow(RatingBook.ProphetEntry entry, int joinOrder) {
    }

    private static final Comparator<RichRow> RICH_ORDER = Comparator.<RichRow, PlayAmount>comparing(r -> r.entry().growth())
            .reversed().thenComparingInt(RichRow::joinOrder);
    private static final Comparator<ProphetRow> PROPHET_ORDER = Comparator.<ProphetRow, BigDecimal>comparing(
            r -> r.entry().accuracy()).reversed().thenComparingInt(ProphetRow::joinOrder);

    /** One sorted board: a list that is never changed after it is built. */
    private static final class Ranking<R> {

        final List<R> rows;
        private final Comparator<R> order;

        Ranking(List<R> rows, Comparator<R> order) {
            this.rows = rows;
            this.order = order;
        }

        /** These rows without {@code removed} and with {@code added}, in one pass; the order is total, so a removed row is found by comparison. */
        Ranking<R> with(List<R> removed, List<R> added) {
            if (removed.isEmpty() && added.isEmpty()) {
                return this;
            }
            List<R> remove = new ArrayList<>(removed);
            remove.sort(order);
            List<R> add = new ArrayList<>(added);
            add.sort(order);
            List<R> out = new ArrayList<>(rows.size() + add.size());
            int r = 0;
            int a = 0;
            for (R row : rows) {
                if (r < remove.size() && order.compare(row, remove.get(r)) == 0) {
                    r++;
                    continue;
                }
                while (a < add.size() && order.compare(add.get(a), row) < 0) {
                    out.add(add.get(a++));
                }
                out.add(row);
            }
            while (a < add.size()) {
                out.add(add.get(a++));
            }
            if (r != remove.size()) {
                throw new IllegalStateException("a leaderboard row to remove was not there: " + remove.get(r));
            }
            return new Ranking<>(Collections.unmodifiableList(out), order);
        }

        static <R> Ranking<R> sorted(List<R> rows, Comparator<R> order) {
            List<R> copy = new ArrayList<>(rows);
            copy.sort(order);
            return new Ranking<>(Collections.unmodifiableList(copy), order);
        }
    }

    private record Board(Ranking<RichRow> rich, Ranking<ProphetRow> prophets) {
    }

    private static final Board NO_BOARD = new Board(Ranking.sorted(List.of(), RICH_ORDER), Ranking.sorted(List.of(), PROPHET_ORDER));

    private final Map<Season, Board> boards;

    private SeasonBoards(Map<Season, Board> boards) {
        this.boards = boards;
    }

    /** The "богачи" board of the season, best first; empty for a season nobody has a record of. */
    List<RichRow> rich(Season season) {
        return boards.getOrDefault(season, NO_BOARD).rich().rows;
    }

    /** The "пророки" board of the season, best first. */
    List<ProphetRow> prophets(Season season) {
        return boards.getOrDefault(season, NO_BOARD).prophets().rows;
    }

    private static Optional<RichRow> richRow(ReadViews.PlayerView view, Season season) {
        if (view == null || view.retired()) {
            return Optional.empty();
        }
        return RatingBook.richEntry(view.account(), season, RatingBook.DEFAULT_ROUNDS_THRESHOLD, null)
                .map(e -> new RichRow(e, view.joinOrder()));
    }

    private static Optional<ProphetRow> prophetRow(ReadViews.PlayerView view, Season season) {
        if (view == null || view.retired()) {
            return Optional.empty();
        }
        return RatingBook.prophetEntry(view.account(), season, RatingBook.DEFAULT_ROUNDS_THRESHOLD, null)
                .map(e -> new ProphetRow(e, view.joinOrder()));
    }

    /** The boards of all these players, sorted from scratch: for a full publication. */
    static SeasonBoards of(Collection<ReadViews.PlayerView> all) {
        Map<Season, List<RichRow>> rich = new HashMap<>();
        Map<Season, List<ProphetRow>> prophets = new HashMap<>();
        for (ReadViews.PlayerView view : all) {
            for (Season season : view.account().seasons().keySet()) {
                richRow(view, season).ifPresent(r -> rich.computeIfAbsent(season, s -> new ArrayList<>()).add(r));
                prophetRow(view, season).ifPresent(r -> prophets.computeIfAbsent(season, s -> new ArrayList<>()).add(r));
            }
        }
        Set<Season> seasons = new HashSet<>(rich.keySet());
        seasons.addAll(prophets.keySet());
        Map<Season, Board> boards = new HashMap<>();
        for (Season season : seasons) {
            boards.put(season, new Board(Ranking.sorted(rich.getOrDefault(season, List.of()), RICH_ORDER),
                    Ranking.sorted(prophets.getOrDefault(season, List.of()), PROPHET_ORDER)));
        }
        return new SeasonBoards(Map.copyOf(boards));
    }

    /**
     * These boards after the players in {@code after} moved from their view in {@code before} (none: they were not there) to the one
     * in {@code after}. Only the seasons the player has a record of, before or after, are looked at, and only a row that differs is moved.
     */
    SeasonBoards with(Map<String, ReadViews.PlayerView> before, Map<String, ReadViews.PlayerView> after) {
        Map<Season, List<RichRow>> richOut = new HashMap<>();
        Map<Season, List<RichRow>> richIn = new HashMap<>();
        Map<Season, List<ProphetRow>> prophetOut = new HashMap<>();
        Map<Season, List<ProphetRow>> prophetIn = new HashMap<>();
        Set<Season> touched = new HashSet<>();
        for (Map.Entry<String, ReadViews.PlayerView> e : after.entrySet()) {
            ReadViews.PlayerView old = before.get(e.getKey());
            ReadViews.PlayerView now = e.getValue();
            Set<Season> seasons = new HashSet<>();
            if (old != null) {
                seasons.addAll(old.account().seasons().keySet());
            }
            seasons.addAll(now.account().seasons().keySet());
            for (Season season : seasons) {
                Optional<RichRow> was = richRow(old, season);
                Optional<RichRow> is = richRow(now, season);
                if (!was.equals(is)) {
                    was.ifPresent(r -> richOut.computeIfAbsent(season, s -> new ArrayList<>()).add(r));
                    is.ifPresent(r -> richIn.computeIfAbsent(season, s -> new ArrayList<>()).add(r));
                    touched.add(season);
                }
                Optional<ProphetRow> wasProphet = prophetRow(old, season);
                Optional<ProphetRow> isProphet = prophetRow(now, season);
                if (!wasProphet.equals(isProphet)) {
                    wasProphet.ifPresent(r -> prophetOut.computeIfAbsent(season, s -> new ArrayList<>()).add(r));
                    isProphet.ifPresent(r -> prophetIn.computeIfAbsent(season, s -> new ArrayList<>()).add(r));
                    touched.add(season);
                }
            }
        }
        if (touched.isEmpty()) {
            return this;
        }
        Map<Season, Board> next = new HashMap<>(boards);
        for (Season season : touched) {
            Board board = boards.getOrDefault(season, NO_BOARD);
            next.put(season, new Board(
                    board.rich().with(richOut.getOrDefault(season, List.of()), richIn.getOrDefault(season, List.of())),
                    board.prophets().with(prophetOut.getOrDefault(season, List.of()), prophetIn.getOrDefault(season, List.of()))));
        }
        return new SeasonBoards(Map.copyOf(next));
    }

    /** What a test compares: the non-empty boards as plain lists (the incremental one may keep an emptied board). */
    Map<Season, List<List<?>>> forTest() {
        Map<Season, List<List<?>>> out = new HashMap<>();
        boards.forEach((season, board) -> {
            if (!board.rich().rows.isEmpty() || !board.prophets().rows.isEmpty()) {
                out.put(season, List.of(board.rich().rows, board.prophets().rows));
            }
        });
        return out;
    }
}
