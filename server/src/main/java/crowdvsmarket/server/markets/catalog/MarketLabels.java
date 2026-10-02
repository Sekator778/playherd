package crowdvsmarket.server.markets.catalog;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The card texts of a Polymarket market: its ticker ({@link #symbol}) and its subtitle ({@link #subtitle}).
 *
 * <p><b>The ticker</b> is at most {@value #MAX_LENGTH} characters, upper case, and <b>unique among the active
 * markets</b> (the caller passes their symbols): the first candidate that is not taken wins, then a numeric
 * suffix ({@code PHI}, {@code PHI2}). The candidates, in order:
 * <ul>
 *   <li><b>A game</b> (a market that has a kick-off, a game id or teams): {@code DRAW} for a market on a draw
 *       (it is on neither team); otherwise the <i>abbreviation</i> of the team the market is on when Gamma gives
 *       one ({@code teams[].abbreviation} of the event — only the {@code events/keyset} answer has it), else the
 *       team's name shortened to {@value #MAX_LENGTH} characters ({@link #shorten}). The team is outcome 0
 *       ("Steelers"), or, when outcome 0 is a bare Yes/No, the market's own label for it
 *       ({@code groupItemTitle}: "Portugal"), or the one the question names.</li>
 *   <li><b>A person or a subject</b> (politics, futures): the <i>last capitalised word of the name</i>. The
 *       name is what the market itself uses as its short label when Gamma gives one — {@code groupItemTitle}
 *       ("Luiz Inácio Lula da Silva" → {@code SILVA}), provided the question contains it; otherwise the first
 *       run of capitalised words of the question ("Will Elon Musk post …" → {@code MUSK}, "Will the U.S. invade
 *       Iran …" → {@code IRAN}).</li>
 *   <li>Then the other words of the name, then the other significant words of the question, in order: this is
 *       how a clash is resolved with "the next distinctive word".</li>
 * </ul>
 * <b>Generic words are never a ticker</b>: the country however it is written ("US", "U.S.", "USA"), question and
 * filler words (Will, The, A, Who, Which, Before, After, By, …), months, numbers and years, one-letter words.
 * A bare Yes/No/Over/Under/Up/Down outcome names nothing either.
 *
 * <p><b>The subtitle</b> says what the card's outcome is. A game: {@code "<outcome> to win · <league>"} (the
 * outcome is the team; a draw is {@code "Draw"}), the league or tournament being the series of the event when
 * Gamma gives it ({@code series[0].title}, else {@code sport.name}) — it used to repeat the title
 * ("Steelers · Steelers vs. Browns"). Anything else: {@code "<outcome> · <event title>"}, the event title only when
 * it is not the question again.
 */
final class MarketLabels {

    static final int MAX_LENGTH = 8;

    private static final Pattern DRAW = Pattern.compile("\\bdraw\\b", Pattern.CASE_INSENSITIVE);

    private static final Set<String> GENERIC_OUTCOMES = Set.of("yes", "no", "over", "under", "up", "down");

    /** Words that name nothing: question and filler words, the country, months. Numbers and years are skipped by shape. */
    private static final Set<String> GENERIC = Set.of("will", "the", "a", "an", "of", "to", "in", "on", "at", "by",
            "for", "be", "is", "are", "does", "do", "did", "has", "have", "who", "what", "which", "when", "before",
            "after", "vs", "and", "or", "win", "wins", "ou", "us", "usa",
            "january", "february", "march", "april", "may", "june", "july", "august", "september", "october",
            "november", "december", "jan", "feb", "mar", "apr", "jun", "jul", "aug", "sep", "sept", "oct", "nov",
            "dec");

    /** Lowercase words that sit inside a name ("Tarcisio de Freitas", "Luiz Inácio Lula da Silva"). */
    private static final Set<String> PARTICLES = Set.of("de", "da", "do", "dos", "das", "di", "du", "del", "della",
            "van", "von", "der", "den", "la", "le", "el", "al", "bin", "ibn", "ben");

    private MarketLabels() {
    }

    /** One word of a text with its punctuation taken out ({@code U.S.} is {@code US}), and whether it began upper case. */
    private record Word(String text, boolean capitalised) {

        String lower() {
            return text.toLowerCase(Locale.ROOT);
        }

        /** Not a filler, not the country, not a month, not a number or a year, not one letter. */
        boolean significant() {
            return text.length() >= 2 && !GENERIC.contains(lower()) && !text.chars().allMatch(Character::isDigit);
        }
    }

    // ------------------------------------------------------------------------------------- symbol

    /** The ticker, with no other symbol taken into account. */
    static String symbol(GammaMarket market) {
        return symbol(market, Set.of());
    }

    /** The ticker, different from every symbol of {@code taken} (compared without case). */
    static String symbol(GammaMarket market, Set<String> taken) {
        Set<String> used = new LinkedHashSet<>();
        for (String symbol : taken) {
            used.add(symbol.toUpperCase(Locale.ROOT));
        }
        List<String> candidates = candidates(market);
        for (String candidate : candidates) {
            if (!used.contains(candidate)) {
                return candidate;
            }
        }
        String base = candidates.get(0);
        for (int n = 2; ; n++) {
            String suffix = Integer.toString(n);
            String symbol = clip(base, MAX_LENGTH - suffix.length()) + suffix;
            if (!used.contains(symbol)) {
                return symbol;
            }
        }
    }

    /** The candidates in the order they are tried: distinct, upper case, at most {@value #MAX_LENGTH} characters, never empty. */
    static List<String> candidates(GammaMarket market) {
        Set<String> out = new LinkedHashSet<>();
        if (isGameLike(market)) {
            gameCandidates(market, out);
        } else {
            subjectCandidates(market, out);
        }
        addWords(out, words(market.question()), false);
        out.add(clip("PM" + market.id(), MAX_LENGTH));
        return new ArrayList<>(out);
    }

    private static void gameCandidates(GammaMarket market, Set<String> out) {
        if (DRAW.matcher(market.question()).find()) {
            out.add("DRAW"); // "Will A vs. B end in a draw?" is not a market on A
        }
        String subject = subject(market);
        String abbreviation = abbreviation(market, subject);
        if (abbreviation != null) {
            out.add(clip(abbreviation, MAX_LENGTH));
        }
        if (subject != null) {
            out.add(shorten(subject));
            List<Word> words = words(subject);
            addWords(out, words, true); // the other words of the team's name
        }
    }

    /** The team a game market is on: outcome 0, else the market's own label for it, else the one its question names. */
    private static String subject(GammaMarket market) {
        String outcome = market.outcome().trim();
        if (!GENERIC_OUTCOMES.contains(outcome.toLowerCase(Locale.ROOT))) {
            return outcome;
        }
        if (market.isMoneyline() && market.groupItemTitle() != null) {
            return market.groupItemTitle();
        }
        GammaEvent.Team named = teamNamedIn(market);
        if (named != null) {
            return named.name();
        }
        List<Word> run = firstNameRun(words(market.question()));
        return run.isEmpty() ? null : String.join(" ", run.stream().map(Word::text).toList());
    }

    /** The abbreviation Gamma gives for the team (the event's {@code teams}), or null. */
    private static String abbreviation(GammaMarket market, String subject) {
        if (market.event() == null) {
            return null;
        }
        if (subject != null) {
            for (GammaEvent.Team team : market.event().teams()) {
                if (team.abbreviation() != null && team.name().equalsIgnoreCase(subject.trim())) {
                    return team.abbreviation();
                }
            }
        }
        GammaEvent.Team named = teamNamedIn(market);
        return named == null ? null : named.abbreviation();
    }

    /** The first team (by position) whose name the question mentions and that has an abbreviation. */
    private static GammaEvent.Team teamNamedIn(GammaMarket market) {
        if (market.event() == null) {
            return null;
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
        return first;
    }

    private static void subjectCandidates(GammaMarket market, Set<String> out) {
        List<Word> questionWords = words(market.question());
        String label = market.groupItemTitle();
        if (label != null && market.question().toLowerCase(Locale.ROOT).contains(label.toLowerCase(Locale.ROOT))) {
            List<Word> run = firstNameRun(words(label)); // the name the market itself uses as its short label
            if (!run.isEmpty()) {
                out.add(clip(last(run).text(), MAX_LENGTH));
                for (int i = run.size() - 2; i >= 0; i--) {
                    if (run.get(i).capitalised()) {
                        out.add(clip(run.get(i).text(), MAX_LENGTH));
                    }
                }
            }
        }
        List<Word> run = firstNameRun(questionWords);
        if (!run.isEmpty()) {
            out.add(clip(last(run).text(), MAX_LENGTH));
            for (int i = run.size() - 2; i >= 0; i--) {
                out.add(clip(run.get(i).text(), MAX_LENGTH));
            }
        }
    }

    /** The words of a text, punctuation taken out; a hyphen or a slash separates words ("Ocasio-Cortez", "O/U"). */
    private static List<Word> words(String text) {
        List<Word> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        for (String raw : text.split("[\\s\\-–—/]+")) {
            String word = raw.replaceAll("(?i)['’]s$", "").replaceAll("[^\\p{L}\\p{N}]", "");
            if (!word.isEmpty()) {
                out.add(new Word(word, Character.isUpperCase(word.codePointAt(0))));
            }
        }
        return out;
    }

    /**
     * The first run of capitalised significant words — a name — with the lowercase particles inside it
     * ({@code Tarcisio de Freitas}); empty when there is none.
     */
    private static List<Word> firstNameRun(List<Word> words) {
        List<Word> run = new ArrayList<>();
        for (int i = 0; i < words.size(); i++) {
            Word word = words.get(i);
            if (word.capitalised() && word.significant()) {
                run.add(word);
            } else if (!run.isEmpty() && PARTICLES.contains(word.lower()) && i + 1 < words.size()
                    && words.get(i + 1).capitalised() && words.get(i + 1).significant()) {
                run.add(word); // a particle inside the name
            } else if (!run.isEmpty()) {
                break;
            }
        }
        return run;
    }

    /** The last capitalised word of a name run. */
    private static Word last(List<Word> run) {
        for (int i = run.size() - 1; i >= 0; i--) {
            if (run.get(i).capitalised()) {
                return run.get(i);
            }
        }
        return run.get(run.size() - 1);
    }

    /** Adds the significant words, as tickers; with {@code reversed} the last one first (a nickname before a city). */
    private static void addWords(Set<String> out, List<Word> words, boolean reversed) {
        for (int i = 0; i < words.size(); i++) {
            Word word = words.get(reversed ? words.size() - 1 - i : i);
            if (word.significant()) {
                out.add(clip(word.text(), MAX_LENGTH));
            }
        }
    }

    /**
     * A team's name as a ticker of at most {@value #MAX_LENGTH} characters: the name itself when it fits,
     * else its last word (the nickname: {@code PHILLIES}) when that has at least four letters, else the
     * start of the name with its spaces taken out.
     */
    static String shorten(String name) {
        String compact = name.replaceAll("[^\\p{L}\\p{N}]", "");
        if (compact.length() <= MAX_LENGTH) {
            return compact.toUpperCase(Locale.ROOT);
        }
        List<Word> words = words(name);
        if (words.size() > 1) {
            String nickname = words.get(words.size() - 1).text();
            if (nickname.length() >= 4) {
                return clip(nickname, MAX_LENGTH);
            }
        }
        return clip(compact, MAX_LENGTH);
    }

    private static String clip(String word, int max) {
        String upper = word.toUpperCase(Locale.ROOT);
        return upper.length() <= max ? upper : upper.substring(0, max);
    }

    /** A game: it has a kick-off, a game id or teams. */
    static boolean isGameLike(GammaMarket market) {
        return market.isGame() || (market.event() != null
                && (market.event().gameId() != null || !market.event().teams().isEmpty()));
    }

    // ----------------------------------------------------------------------------------- subtitle

    /** What the card's outcome is, see the class comment. */
    static String subtitle(GammaMarket market) {
        String league = market.event() == null ? null : market.event().league();
        if (isGameLike(market)) {
            String what;
            if (DRAW.matcher(market.question()).find()) {
                what = "Draw";
            } else if (market.isMoneyline() || !GENERIC_OUTCOMES.contains(market.outcome().trim().toLowerCase(Locale.ROOT))) {
                String subject = subject(market);
                what = (subject == null ? market.outcome() : subject) + " to win";
            } else {
                what = market.outcome(); // Over / Under, a spread: not a bet on a winner
            }
            return league == null ? what : what + " · " + league;
        }
        String eventTitle = market.event() == null ? null : market.event().title();
        if (eventTitle == null || sameText(eventTitle, market.question())) {
            return market.outcome();
        }
        return market.outcome() + " · " + eventTitle;
    }

    /** The same words, whatever the case and punctuation. */
    private static boolean sameText(String a, String b) {
        return normalize(a).equals(normalize(b));
    }

    private static String normalize(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }
}
