package crowdvsmarket.server.markets.catalog;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The short ticker of a Polymarket market for its card (at most 8 characters, upper case):
 * <ol>
 *   <li>{@code DRAW} for a market on a draw (it is on neither team);</li>
 *   <li>the abbreviation of the team the market is about, when the event's teams are known — the team
 *       named by outcome 0, else the one the question mentions ({@code prt} for "Will Portugal win …");</li>
 *   <li>otherwise the first significant word of outcome 0, unless that is a bare Yes/No/Over/Under/
 *       Up/Down ({@code Steelers});</li>
 *   <li>otherwise the first significant word of the question ({@code Will the U.S. invade Iran …}
 *       → {@code US}).</li>
 * </ol>
 * A significant word is one that is not a filler word ("will", "the", …) or a bare number, with the
 * punctuation taken out of it ({@code U.S.} is {@code US}).
 */
final class MarketLabels {

    static final int MAX_LENGTH = 8;

    private static final Pattern DRAW = Pattern.compile("\\bdraw\\b", Pattern.CASE_INSENSITIVE);

    private static final Set<String> GENERIC_OUTCOMES = Set.of("yes", "no", "over", "under", "up", "down");

    private static final Set<String> FILLER = Set.of("will", "the", "a", "an", "of", "to", "in", "on", "at", "by",
            "for", "be", "is", "are", "does", "do", "did", "has", "have", "who", "what", "which", "when", "before",
            "after", "vs", "and", "or", "win", "wins");

    private MarketLabels() {
    }

    static String symbol(GammaMarket market) {
        if (DRAW.matcher(market.question()).find()) {
            return "DRAW"; // "Will A vs. B end in a draw?" is not a market on A
        }
        String fromTeam = teamAbbreviation(market);
        if (fromTeam != null) {
            return clip(fromTeam);
        }
        if (!GENERIC_OUTCOMES.contains(market.outcome().trim().toLowerCase(Locale.ROOT))) {
            String word = firstSignificantWord(market.outcome());
            if (word != null) {
                return clip(word);
            }
        }
        String word = firstSignificantWord(market.question());
        return word != null ? clip(word) : clip("PM" + market.id());
    }

    private static String teamAbbreviation(GammaMarket market) {
        if (market.event() == null) {
            return null;
        }
        String outcome = market.outcome().trim();
        for (GammaEvent.Team team : market.event().teams()) {
            if (team.abbreviation() != null && team.name().equalsIgnoreCase(outcome)) {
                return team.abbreviation();
            }
        }
        String question = market.question().toLowerCase(Locale.ROOT);
        GammaEvent.Team first = null;
        int firstAt = Integer.MAX_VALUE;
        for (GammaEvent.Team team : market.event().teams()) {
            int at = question.indexOf(team.name().toLowerCase(Locale.ROOT));
            if (team.abbreviation() != null && at >= 0 && at < firstAt) {
                first = team;
                firstAt = at;
            }
        }
        return first == null ? null : first.abbreviation();
    }

    /** The first word of {@code text} that is neither filler nor a bare number, punctuation removed; null when there is none. */
    static String firstSignificantWord(String text) {
        if (text == null) {
            return null;
        }
        for (String raw : text.split("\\s+")) {
            String word = raw.replaceAll("(?i)['’]s$", "").replaceAll("[^\\p{L}\\p{N}]", "");
            if (word.isEmpty() || FILLER.contains(word.toLowerCase(Locale.ROOT)) || word.chars().allMatch(Character::isDigit)) {
                continue;
            }
            return word;
        }
        return null;
    }

    private static String clip(String word) {
        String upper = word.toUpperCase(Locale.ROOT);
        return upper.length() <= MAX_LENGTH ? upper : upper.substring(0, MAX_LENGTH);
    }
}
