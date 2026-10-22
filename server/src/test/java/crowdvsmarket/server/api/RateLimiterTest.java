package crowdvsmarket.server.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import crowdvsmarket.server.config.GameConfig;
import org.junit.jupiter.api.Test;

/**
 * {@link RateLimiter}'s token bucket without the application: the shared budget of {@code herd.ratelimit.*} is what it
 * was, and a caller may bring a budget of its own (Р-81: {@code submitFeedback}).
 */
class RateLimiterTest {

    private static RateLimiter limiter(int capacity, double refillPerSecond) {
        GameConfig config = mock(GameConfig.class);
        when(config.rateLimitCapacity()).thenReturn(capacity);
        when(config.rateLimitRefillPerSecond()).thenReturn(refillPerSecond);
        RateLimiter limiter = new RateLimiter();
        limiter.config = config;
        return limiter;
    }

    @Test
    void theSharedBudgetStillComesFromTheConfig() {
        RateLimiter limiter = limiter(2, 0);

        assertThat(limiter.tryAcquire("1.2.3.4:placeBet")).isTrue();
        assertThat(limiter.tryAcquire("1.2.3.4:placeBet")).isTrue();
        assertThat(limiter.tryAcquire("1.2.3.4:placeBet")).isFalse();
        assertThat(limiter.tryAcquire("1.2.3.4:cancelBet")).as("each key has a bucket of its own").isTrue();
        assertThat(limiter.tryAcquire("5.6.7.8:placeBet")).isTrue();
    }

    @Test
    void aBudgetOfItsOwnIsUsedInsteadOfTheSharedOne() {
        RateLimiter limiter = limiter(30, 0.5);

        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryAcquire("1.2.3.4:submitFeedback", 5, 0.0)).as("message %d", i + 1).isTrue();
        }
        assertThat(limiter.tryAcquire("1.2.3.4:submitFeedback", 5, 0.0)).as("the sixth, of a bucket of five").isFalse();
        assertThat(limiter.tryAcquire("5.6.7.8:submitFeedback", 5, 0.0)).as("another address, a full bucket").isTrue();
    }

    @Test
    void theBudgetOfOneKeyDoesNotTouchTheBudgetOfAnother() {
        RateLimiter limiter = limiter(2, 0);

        for (int i = 0; i < 5; i++) {
            limiter.tryAcquire("1.2.3.4:submitFeedback", 5, 0.0);
        }

        assertThat(limiter.tryAcquire("1.2.3.4:placeBet")).as("the shared budget of the same address is full").isTrue();
        assertThat(limiter.tryAcquire("1.2.3.4:placeBet")).isTrue();
        assertThat(limiter.tryAcquire("1.2.3.4:placeBet")).isFalse();
    }

    @Test
    void aBucketStartsFullAndNeverHoldsMoreThanItsCapacity() throws InterruptedException {
        RateLimiter limiter = limiter(30, 0.5);

        assertThat(limiter.tryAcquire("a", 1, 1000.0)).isTrue();
        assertThat(limiter.tryAcquire("a", 1, 1000.0)).as("one token, used; nothing has flowed back yet").isFalse();
        Thread.sleep(50); // 50 tokens would have flowed back at 1000 a second — the bucket takes one
        assertThat(limiter.tryAcquire("a", 1, 1000.0)).isTrue();
        assertThat(limiter.tryAcquire("a", 1, 1000.0)).as("capacity 1: the rest was never kept").isFalse();
    }

    @Test
    void aSlowRefillGivesBackNothingInTheSpanOfATest() {
        // 6 an hour, as herd.feedback.rate-limit.refill-per-hour: a token comes every ten minutes
        RateLimiter limiter = limiter(30, 0.5);
        double perSecond = 6 / 3600.0;

        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryAcquire("a", 5, perSecond)).isTrue();
        }
        assertThat(limiter.tryAcquire("a", 5, perSecond)).isFalse();
    }

    @Test
    void aBucketIdleForLongerThanItsRefillIsForgottenAndOneThatIsNotStays() throws InterruptedException {
        RateLimiter limiter = limiter(30, 0.5);
        limiter.tryAcquire("fast", 1, 1000.0); // refills to full in a millisecond
        limiter.tryAcquire("slow", 1, 0.001); // 1000 seconds
        Thread.sleep(30);

        limiter.sweep();

        assertThat(limiter.size()).isEqualTo(1);
        assertThat(limiter.tryAcquire("slow", 1, 0.001)).as("the slow bucket kept its spent token").isFalse();
    }

    @Test
    void theMapNeverGrowsPastItsCapAndANewKeyPastItSharesOneBucket() {
        RateLimiter limiter = limiter(30, 0.5);
        limiter.setMaxBuckets(3);

        for (int i = 0; i < 100; i++) {
            limiter.tryAcquire("k" + i, 2, 0.0);
        }

        assertThat(limiter.size()).isLessThanOrEqualTo(4); // the cap, plus the shared overflow bucket
        assertThat(limiter.tryAcquire("another", 2, 0.0)).as("the overflow bucket is spent").isFalse();
        assertThat(limiter.tryAcquire("k0", 2, 0.0)).as("a key that was in before the cap still has its own").isTrue();
    }

    @Test
    void anIpv6AddressIsKeyedByItsSlash64AndAnIpv4OneByItself() {
        assertThat(RateLimiter.addressKey("2001:db8:1:2:aaaa:bbbb:cccc:dddd"))
                .isEqualTo(RateLimiter.addressKey("2001:db8:1:2:1::9"))
                .isEqualTo("2001:0db8:0001:0002::/64");
        assertThat(RateLimiter.addressKey("2001:db8:1:3::1")).isNotEqualTo(RateLimiter.addressKey("2001:db8:1:2::1"));
        assertThat(RateLimiter.addressKey("fe80::1%eth0")).isEqualTo("fe80:0000:0000:0000::/64");
        assertThat(RateLimiter.addressKey("::ffff:1.2.3.4")).isEqualTo("1.2.3.4");
        assertThat(RateLimiter.addressKey("1.2.3.4")).isEqualTo("1.2.3.4");
        assertThat(RateLimiter.addressKey("unknown")).isEqualTo("unknown");
        assertThat(RateLimiter.addressKey(null)).isNull();
    }
}
