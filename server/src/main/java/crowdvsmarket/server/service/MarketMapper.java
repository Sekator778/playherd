package crowdvsmarket.server.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import crowdvsmarket.engine.crowd.CrowdAccount;
import crowdvsmarket.engine.ledger.PlayAmount;
import crowdvsmarket.engine.round.Bet;
import crowdvsmarket.server.dto.Enums;
import crowdvsmarket.server.dto.Markets;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.prices.PriceQuote;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Collection;

/**
 * The pure part of the markets read model ({@code MarketViewService}): how a market, a quote, the pending
 * bets of a round and a crowd account turn into the fields of contract.ts' {@code MarketDto}. No state, no
 * I/O, no locks — so each rule is tested on its own.
 *
 * <p>Every price that leaves the server is a RAW price with the market's {@code priceDecimals} digits
 * ({@link #rawPrice}): a PERP with lot exponent 0 has 2, one with lot exponent 4 has 6, an ODDS market (lot
 * exponent 4) has 6 — never an engine (lot) price (docs/research/markets_architecture.md Т-3).
 */
final class MarketMapper {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final BigDecimal STARTING_CAPITAL = CrowdAccount.STARTING_CAPITAL;

    private MarketMapper() {
    }

    /** {@code raw} as the market's price string: exactly {@code priceDecimals} fractional digits (HALF_UP). */
    static String rawPrice(Market market, BigDecimal raw) {
        return raw.setScale(market.priceDecimals(), RoundingMode.HALF_UP).toPlainString();
    }

    static Markets.MarketPriceDto price(Market market, PriceQuote quote) {
        return new Markets.MarketPriceDto(rawPrice(market, quote.raw()), quote.asOfMs(), quote.change24h(),
                quote.stale(), quote.venue());
    }

    /** The live split of the bets of a round that is open for betting: how many players, the stake on each side,
     *  and the side the crowd is on. */
    record Split(int participantCount, PlayAmount upStake, PlayAmount downStake) {

        /** The side with more stake; {@code null} for a tie, 0 vs 0 included (Р-5: no majority, no crowd trade). */
        Enums.Direction majorityDirection() {
            int cmp = upStake.compareTo(downStake);
            return cmp == 0 ? null : cmp > 0 ? Enums.Direction.UP : Enums.Direction.DOWN;
        }
    }

    static Split split(Collection<Bet> bets) {
        PlayAmount up = PlayAmount.ZERO;
        PlayAmount down = PlayAmount.ZERO;
        for (Bet bet : bets) {
            if (bet.direction() == crowdvsmarket.engine.round.Direction.UP) {
                up = up.plus(bet.stake());
            } else {
                down = down.plus(bet.stake());
            }
        }
        return new Split(bets.size(), up, down);
    }

    /** {@code capital / 100000 - 1}: how the crowd account has done since its start (a fraction, 0.034 = +3.4%). */
    static double returnPct(BigDecimal capital) {
        return returnPct(capital, STARTING_CAPITAL);
    }

    /** {@code capital / startingCapital - 1}: the same for several accounts together (their sums). */
    static double returnPct(BigDecimal capital, BigDecimal startingCapital) {
        return capital.divide(startingCapital, MathContext.DECIMAL128).subtract(BigDecimal.ONE).doubleValue();
    }

    /**
     * When the game a market is about starts, for {@code MarketDto.eventStartsAtMs}: the number {@code
     * "gameStartMs"} of the market's {@code sourceMeta} when there is one, otherwise the {@code "gameStartTime"}
     * the Polymarket catalog stores (Gamma's own spelling, {@code "2026-10-01 18:45:00+00"}, or ISO). {@code
     * null} for everything that is not a game, and for a value that cannot be read — a lobby must never fail
     * on a malformed note of a catalog.
     */
    static Long eventStartsAtMs(Market market) {
        String meta = market.sourceMeta();
        if (meta == null || meta.isBlank()) {
            return null;
        }
        try {
            JsonNode node = JSON.readTree(meta);
            JsonNode ms = node.path("gameStartMs");
            if (ms.isIntegralNumber() && ms.canConvertToLong() && ms.asLong() > 0) {
                return ms.asLong();
            }
            if (ms.isTextual() && ms.asText().matches("[1-9]\\d{0,17}")) {
                return Long.parseLong(ms.asText());
            }
            JsonNode time = node.path("gameStartTime");
            return time.isTextual() ? parseInstantMs(time.asText()) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** ISO instants and offsets, and Gamma's space-separated ones with a two-digit offset ({@code +00}). */
    static Long parseInstantMs(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim();
        try {
            return Instant.parse(s).toEpochMilli();
        } catch (DateTimeParseException ignored) {
            // not a plain instant
        }
        try {
            String iso = s.replace(' ', 'T');
            if (iso.matches(".*[+-]\\d{2}$")) {
                iso += ":00"; // "+00" → "+00:00"
            }
            return OffsetDateTime.parse(iso).toInstant().toEpochMilli();
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }
}
