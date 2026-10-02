package crowdvsmarket.server.service;

import java.util.ArrayList;
import java.util.List;

/**
 * The thinning rule of {@code priceSeries} (contract.ts {@code Api.priceSeries}: "At most 600 points; when
 * more exist … thinned evenly so they still span the whole requested range"): from {@code n > max} ticks,
 * oldest first, keep {@code max} of them at evenly spaced positions — the oldest and the NEWEST always among
 * them (the newest tick is the live price; a chart that loses it lags behind the market) — and no more than
 * {@code max} in any case. Fewer ticks than the cap are returned as they are.
 *
 * <p>The position of the k-th kept tick is {@code round(k · (n − 1) / (max − 1))}, in integer arithmetic.
 * With {@code n > max} that step is above 1, so positions are strictly increasing: no tick is kept twice and
 * the order is preserved.
 */
final class PriceThinning {

    private PriceThinning() {
    }

    /** @param max at least 2 (a range needs both its ends) */
    static <T> List<T> thin(List<T> ticks, int max) {
        if (max < 2) {
            throw new IllegalArgumentException("max must be at least 2, got " + max);
        }
        int n = ticks.size();
        if (n <= max) {
            return ticks;
        }
        List<T> kept = new ArrayList<>(max);
        long span = max - 1L;
        for (int k = 0; k < max; k++) {
            long position = (k * (n - 1L) + span / 2) / span; // nearest, ties up
            kept.add(ticks.get((int) position));
        }
        return kept;
    }
}
