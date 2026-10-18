package crowdvsmarket.server;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import crowdvsmarket.engine.ledger.LedgerBook;
import crowdvsmarket.engine.ledger.PlayAmount;
import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.chain.ChainSvcClient;
import crowdvsmarket.server.dto.ApiException;
import crowdvsmarket.server.dto.Economy;
import crowdvsmarket.server.dto.Enums;
import crowdvsmarket.server.dto.Rounds;
import crowdvsmarket.server.markets.Category;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.markets.MarketKind;
import crowdvsmarket.server.service.EconomyLimits;
import crowdvsmarket.server.testsupport.EconomyProfile;
import crowdvsmarket.server.testsupport.LiveGameTestBase;
import crowdvsmarket.server.testsupport.LogCapture;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

/**
 * Economy stage B (Р-92, docs/product/economy_implementation_plan.md B1-B7) through the service, with the economy switched on
 * ({@link EconomyProfile}): TREASURY opens at the cutover with 1 000 000 PLAY, so the 0.25% / 1% limits of B5 are a few hundred thousand of
 * notional and a handful of bets reaches them.
 *
 * <p>Market "SOL" (one of the plan's twelve): caps 600 / 300, {@code r_m} 1.0 bp, stress move 160.1 bp for the 3-minute round.
 */
@QuarkusTest
@TestProfile(EconomyProfile.class)
class EconomyStageBFlowTest extends LiveGameTestBase {

    private static final Interval HOURLY = Interval.HOURLY;

    @ConfigProperty(name = "herd.operator.token")
    String operatorToken;

    /** Every test starts from a game that has just had its cutover: TREASURY at the opening total, nobody signed up. */
    @org.junit.jupiter.api.BeforeEach
    void freshBooks() {
        crowdvsmarket.server.testsupport.CleanDatabasePerTestClass.resetDatabaseAndMemory();
    }

    private Market listPriced(String id, String symbol) {
        Market market = list(id, MarketKind.PERP, Category.CRYPTO, symbol, 25, 0);
        alignTo(market, HOURLY, roundProgress.next("BTC", HOURLY));
        prices.setPrice(market, "100.00");
        return market;
    }

    private LedgerBook ledger() {
        return hub.ledger();
    }

    private static PlayAmount play(String amount) {
        return PlayAmount.fromMoney(new BigDecimal(amount));
    }

    // --- B1: the cutover and the accounts ---

    @Test
    void theGameStartedWithTheCutoverAndAPacksComesFromTreasury() {
        assertThat(ledger().economyActive()).isTrue();
        assertThat(ledger().openingTotal()).isEqualTo(PlayAmount.ofWholePlay(EconomyProfile.OPENING_PLAY));
        PlayAmount before = ledger().treasury();

        String p = signUp("pack");

        assertThat(gameService.account(p).balance()).isEqualTo("10000.000");
        assertThat(ledger().treasury()).isEqualTo(before.minus(PlayAmount.ofWholePlay(10_000)));
        assertThat(ledger().identityDelta()).isEqualTo(PlayAmount.ZERO);
    }

    @Test
    void aLostBetGoesToTreasuryFeesToHerdAndAWinIsPaidFromTreasury() throws Exception {
        Market sol = listPriced("T_ECOB_FLOW", "SOL");
        RoundWindow running = roundProgress.next(sol.id(), HOURLY);
        RoundWindow betting = roundProgress.bettingWindow(sol.id(), HOURLY);
        String loser = signUp("loser");
        String winner = signUp("winner");
        PlayAmount treasury0 = ledger().treasury();
        PlayAmount herd0 = ledger().herd();
        gameService.placeBet(loser, bet(betting, Enums.Direction.UP, 100, "200.000"), betting.startMs() - 1);
        gameService.placeBet(winner, bet(betting, Enums.Direction.UP, 2, "200.000"), betting.startMs() - 1);
        assertThat(ledger().herd()).as("fees 2.500 + 0.100 and 0.050 + 0.100").isEqualTo(herd0.plus(play("2.750")));
        assertThat(ledger().openStakes()).isEqualTo(play("400.000"));
        assertThat(ledger().treasury()).as("the open stakes are in no account").isEqualTo(treasury0);

        scheduler.tick(running.endMs());
        prices.script(sol, betting, "100.00", "100.60", "99.01", "100.60"); // x100 long liquidated at 99.02 (200 -> 2.00); x2 long +1.2% = +2.40
        scheduler.tick(betting.endMs());

        // treasury keeps 200 - 2.00 from the loser and pays 2.40 to the winner
        assertThat(ledger().treasury()).isEqualTo(treasury0.plus(play("195.600")));
        assertThat(gameService.account(loser).balance()).isEqualTo("9799.400");
        assertThat(gameService.account(winner).balance()).isEqualTo("10002.250");
        assertThat(ledger().openStakes()).isEqualTo(PlayAmount.ZERO);
        assertThat(ledger().identityDelta()).isEqualTo(PlayAmount.ZERO);

        // the liquidation parameters are in the event: the cap of the short round and kappa
        String payload = db.read(c -> db.queryOne(c, "SELECT payload::text AS p FROM game_events WHERE event_type = 'BetPlaced' "
                + "AND payload->>'playerId' = ?", rs -> rs.getString("p"), loser));
        assertThat(payload.replaceAll("\\s", "")).contains("\"liqCap\":600").contains("\"liqKappa\":\"0.1\"");

        // a replay of the whole log gives the same books
        Map<String, Object> before = books(loser, winner);
        rebuild.rebuild();
        assertThat(books(loser, winner)).isEqualTo(before);
    }

    private Map<String, Object> books(String... players) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("treasury", ledger().treasury());
        out.put("herd", ledger().herd());
        out.put("openStakes", ledger().openStakes());
        for (String p : players) {
            out.put("balance " + p, ledger().balance(p));
            out.put("packs " + p, ledger().packCredited(p));
        }
        return out;
    }

    @Test
    void aCancelledBetGivesBackTheStakeAndBothFeesFromHerd() {
        Market sol = listPriced("T_ECOB_CANCEL", "SOL");
        RoundWindow betting = roundProgress.bettingWindow(sol.id(), HOURLY);
        String p = signUp("cancel");
        PlayAmount herd0 = ledger().herd();

        gameService.placeBet(p, bet(betting, Enums.Direction.UP, 100, "200.000"), betting.startMs() - 1);
        assertThat(ledger().herd()).isEqualTo(herd0.plus(play("2.600")));
        gameService.cancelBet(p, betting.roundId(), betting.startMs() - 1);

        assertThat(ledger().herd()).isEqualTo(herd0);
        assertThat(gameService.account(p).balance()).isEqualTo("10000.000");
        assertThat(ledger().identityDelta()).isEqualTo(PlayAmount.ZERO);
    }

    // --- B2: the pack cannot be withdrawn ---

    @Test
    void aStarterPackCannotBeWithdrawnOnlyWhatIsAboveIt() {
        String p = signUp("withdraw");

        assertThatThrownBy(() -> gameService.requestWithdrawal(p, "500.000", System.currentTimeMillis()))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(Enums.ApiErrorCode.WITHDRAWAL_EXCEEDS_BALANCE);
                    assertThat(e.getMessage()).contains("starter pack").contains("0.000");
                });

        gameService.creditOnChainDeposit(p, 1, PlayAmount.ofWholePlay(1_000), "0x" + UUID.randomUUID().toString().replace("-", ""),
                1L, System.currentTimeMillis());
        assertThat(ledger().withdrawable(p)).isEqualTo(PlayAmount.ofWholePlay(1_000));
        assertThatThrownBy(() -> gameService.requestWithdrawal(p, "1000.001", System.currentTimeMillis()))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(Enums.ApiErrorCode.WITHDRAWAL_EXCEEDS_BALANCE);
                    assertThat(e.getMessage()).contains("1000.000");
                });
        assertThat(ledger().identityDelta()).isEqualTo(PlayAmount.ZERO);
    }

    // --- B3: new players per IP ---

    @Test
    void atMostThreeNewPlayersPerAddressInADayAndOnlyAHashIsKept() {
        long now = System.currentTimeMillis();
        String ip = "203.0.113." + (now % 200);

        for (int i = 0; i < 3; i++) {
            gameService.registerOrTouchPlayer("txch1ip" + i + UUID.randomUUID().toString().replace("-", ""), now, ip);
        }
        String fourth = "txch1ipfourth" + UUID.randomUUID().toString().replace("-", "");
        assertThatThrownBy(() -> gameService.registerOrTouchPlayer(fourth, now + 1_000, ip))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(Enums.ApiErrorCode.REGISTRATION_LIMIT);
                    assertThat(e.httpStatus()).isEqualTo(429);
                    assertThat(e.getMessage()).contains("3 new players");
                });
        assertThat(gameService.registerOrTouchPlayer("txch1other" + UUID.randomUUID().toString().replace("-", ""), now, ip + "9")
                .isNewPlayer()).as("another address").isTrue();
        // a player who exists can sign in from the address that is over the limit
        String known = "txch1known" + UUID.randomUUID().toString().replace("-", "");
        gameService.registerOrTouchPlayer(known, now, "198.51.100.7");
        assertThat(gameService.registerOrTouchPlayer(known, now + 2_000, ip).isNewPlayer()).isFalse();

        // the address is nowhere: only 64 hex digits of a salted hash, and the rows count the three
        List<String> hashes = db.read(c -> db.queryList(c, "SELECT ip_hash FROM registration_ips", rs -> rs.getString("ip_hash")));
        assertThat(hashes).isNotEmpty().allSatisfy(h -> assertThat(h).matches("[0-9a-f]{64}").doesNotContain(ip));
        long forIp = hashes.stream().filter(h -> h.equals(registrationHash(ip))).count();
        assertThat(forIp).isEqualTo(3);

        // after 24 hours the window has moved on
        assertThat(gameService.registerOrTouchPlayer(fourth, now + 24L * 3_600_000L + 1_000, ip).isNewPlayer()).isTrue();
    }

    private String registrationHash(String ip) {
        return io.quarkus.arc.Arc.container().instance(crowdvsmarket.server.persistence.RegistrationIpRepository.class).get().hash(ip);
    }

    @Test
    void theLimitIsAnswered429OverHttpForANewWalletAndNotForOneThatExists() {
        when(chainSvcClient.authVerify(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new ChainSvcClient.AuthVerifyResult(true, null));
        Map<String, Integer> statuses = new LinkedHashMap<>();
        String first = null;
        for (int i = 0; i < 5; i++) {
            String address = "txch1http" + i + UUID.randomUUID().toString().replace("-", "");
            first = first == null ? address : first;
            statuses.put(address, signIn(address).statusCode());
        }
        assertThat(statuses.values()).containsExactly(200, 200, 200, 429, 429);
        Response refused = signIn("txch1http9" + UUID.randomUUID().toString().replace("-", ""));
        assertThat(refused.jsonPath().getString("error.code")).isEqualTo("REGISTRATION_LIMIT");
        assertThat(refused.jsonPath().getString("error.message")).contains("24 hours");
        assertThat(signIn(first).statusCode()).as("an existing wallet signs in").isEqualTo(200);
    }

    private Response signIn(String address) {
        Response challenge = given().contentType(ContentType.JSON).body(Map.of("args", List.of())).post("/api/rpc/authChallenge");
        Map<String, Object> verify = Map.of("nonce", challenge.jsonPath().getString("nonce"), "address", address,
                "publicKey", "0x" + "ab".repeat(48), "signature", "0x" + "cd".repeat(96));
        return given().contentType(ContentType.JSON).body(Map.of("args", List.of(verify))).post("/api/rpc/authVerify");
    }

    // --- B5: exposure limits ---

    @Test
    void theOpenNotionalOfASideOfARoundIsLimitedAndTheRejectionNamesTheRoomLeft() {
        Market sol = listPriced("T_ECOB_EXPO", "SOL");
        RoundWindow betting = roundProgress.bettingWindow(sol.id(), HOURLY);
        String a = signUp("expoa");
        String b = signUp("expob");
        String c = signUp("expoc");
        long nowMs = betting.startMs() - 1;
        BigDecimal stress = sol.economy().stressFor(HOURLY, sol.kind());
        BigDecimal limit = EconomyLimits.marketLimit(ledger().treasury(), stress);
        assertThat(limit).as("0.25% of about 1 000 000 over 1.601%").isBetween(new BigDecimal("150000"), new BigDecimal("160000"));

        gameService.placeBet(a, bet(betting, Enums.Direction.UP, 600, "200.000"), nowMs);   // notional 120 000
        assertThatThrownBy(() -> gameService.placeBet(b, bet(betting, Enums.Direction.UP, 600, "200.000"), nowMs))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(Enums.ApiErrorCode.EXPOSURE_LIMIT_EXCEEDED);
                    assertThat(e.httpStatus()).isEqualTo(422);
                    BigDecimal room = limit.subtract(new BigDecimal("120000"));
                    assertThat(e.getMessage()).contains(room.stripTrailingZeros().toPlainString())
                            .contains("a stake of at most");
                });
        assertThat(gameService.account(b).balance()).as("a rejected bet reserves nothing").isEqualTo("10000.000");
        // the other side is its own limit; and a smaller bet fits in the room that is left
        gameService.placeBet(b, bet(betting, Enums.Direction.DOWN, 600, "200.000"), nowMs);
        gameService.placeBet(c, bet(betting, Enums.Direction.UP, 300, "100.000"), nowMs);   // notional 30 000, room 34 000
        // replacing a bet does not count the old one twice
        gameService.placeBet(a, bet(betting, Enums.Direction.UP, 600, "200.000"), nowMs);
    }

    @Test
    void theCryptoMarketsShareOneBucketPerSideOfARound() {
        String[] symbols = {"SOL", "XRP", "HYPE", "ETH", "DOGE"};
        List<Market> markets = new java.util.ArrayList<>();
        for (String symbol : symbols) {
            markets.add(listPriced("T_ECOB_BKT_" + symbol, symbol));
        }
        List<String> players = new java.util.ArrayList<>();
        for (String symbol : symbols) {
            players.add(signUp("bkt" + symbol));
        }
        PlayAmount treasury = ledger().treasury();
        BigDecimal bucket = EconomyLimits.cryptoBucketLimit(treasury);
        long nowMs = roundProgress.bettingWindow(markets.get(0).id(), HOURLY).startMs() - 1;
        BigDecimal risk = BigDecimal.ZERO;
        // the first four markets take bets of 250 PLAY each as close under their own limit (0.24% of the treasury at the stress move) as the cap lets
        for (int i = 0; i < 4; i++) {
            Market market = markets.get(i);
            RoundWindow betting = roundProgress.bettingWindow(market.id(), HOURLY);
            assertThat(betting.startMs()).as("the crypto markets' rounds start together").isEqualTo(nowMs + 1);
            BigDecimal stress = market.economy().stressFor(HOURLY, market.kind());
            int leverage = Math.min(market.economy().capFor(HOURLY), treasury.toMoney().multiply(new BigDecimal("0.0024"))
                    .divide(stress, 3, java.math.RoundingMode.DOWN).divide(new BigDecimal(250), 0, java.math.RoundingMode.DOWN).intValue());
            gameService.placeBet(players.get(i), bet(betting, Enums.Direction.UP, leverage, "250.000"), nowMs);
            risk = risk.add(new BigDecimal(250).multiply(BigDecimal.valueOf(leverage)).multiply(stress));
        }
        assertThat(risk).as("four nearly full markets use most of the bucket").isLessThan(bucket)
                .isGreaterThan(bucket.multiply(new BigDecimal("0.8")));
        // DOGE alone has room for 0.25% of the treasury, the bucket has less left: refused, and the message says why
        Market doge = markets.get(4);
        RoundWindow dogeBetting = roundProgress.bettingWindow(doge.id(), HOURLY);
        BigDecimal dogeStress = doge.economy().stressFor(HOURLY, doge.kind());
        BigDecimal bucketRoom = bucket.subtract(risk).divide(dogeStress, 3, java.math.RoundingMode.DOWN);
        assertThat(bucketRoom).isLessThan(EconomyLimits.marketLimit(treasury, dogeStress)).isLessThan(new BigDecimal("72000"));
        assertThatThrownBy(() -> gameService.placeBet(players.get(4), bet(dogeBetting, Enums.Direction.UP, 600, "120.000"), nowMs))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(Enums.ApiErrorCode.EXPOSURE_LIMIT_EXCEEDED);
                    assertThat(e.getMessage()).contains("share one limit").contains(bucketRoom.stripTrailingZeros().toPlainString());
                });
        // the other side of the same round is free, and a bet that fits in the bucket's room is accepted
        gameService.placeBet(players.get(4), bet(dogeBetting, Enums.Direction.DOWN, 600, "120.000"), nowMs);
        gameService.placeBet(players.get(4), bet(dogeBetting, Enums.Direction.UP, 300, "100.000"), nowMs);
    }

    // --- B6: the circuit breaker ---

    @Test
    void withTreasuryBelowNinetyPercentOfItsPeakNewBetsTakeAQuarterOfTheCapAndTheFlagShowsIt() {
        Market sol = listPriced("T_ECOB_BREAKER", "SOL");
        RoundWindow betting = roundProgress.bettingWindow(sol.id(), HOURLY);
        String p = signUp("breaker");
        String winner = signUp("breakerwin");
        long nowMs = betting.startMs() - 1;
        assertThat(gameService.circuitBreakerOpen(nowMs)).isFalse();
        assertThat(adminReconciliation().jsonPath().getBoolean("circuitBreakerOpen")).isFalse();

        // the treasury loses 12% of its peak: a payout of 120 000 PLAY on a stake of 5 000 (booked straight on the ledger)
        winnerTakes(winner, "5000.000", "125000.000");
        try {
            assertThat(gameService.circuitBreakerOpen(nowMs)).isTrue();
            assertThat(adminReconciliation().jsonPath().getBoolean("circuitBreakerOpen")).isTrue();
            assertThatThrownBy(() -> gameService.placeBet(p, bet(betting, Enums.Direction.UP, 151, "200.000"), nowMs))
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.code()).isEqualTo(Enums.ApiErrorCode.INVALID_LEVERAGE);
                        assertThat(e.getMessage()).contains("[2, 150]").contains("90%");
                    });
            Rounds.BetDto placed = gameService.placeBet(p, bet(betting, Enums.Direction.UP, 150, "200.000"), nowMs);
            assertThat(placed.leverage()).isEqualTo(150);
        } finally {
            rebuild.rebuild(); // the booking above was never an event: the replay puts the books back
        }
        assertThat(gameService.circuitBreakerOpen(nowMs)).isFalse();
    }

    /** The winner bets {@code stake} and is paid {@code payout}, on the live ledger only (no event): moves TREASURY down. */
    private void winnerTakes(String player, String stake, String payout) {
        java.time.Instant now = java.time.Instant.now();
        ledger().reserveStake(player, "synthetic-round", play(stake), now);
        ledger().settleRound(player, "synthetic-round", play(stake), play(payout), java.util.Optional.of(true), now);
    }

    // --- B7: the reconciliation ---

    private Response adminReconciliation() {
        return given().header("Authorization", "Bearer " + operatorToken).get("/api/admin/reconciliation");
    }

    @Test
    void theReconciliationAddsUpAndWarnsWhenTheChainDisagrees() {
        signUp("recon");
        PlayAmount booksTotal = ledger().treasury().plus(ledger().herd()).plus(ledger().playersTotal()).plus(ledger().openStakes());
        when(chainSvcClient.playTreasury()).thenReturn(null);

        Response unknown = adminReconciliation();
        assertThat(unknown.statusCode()).isEqualTo(200);
        assertThat(unknown.jsonPath().getString("issued")).isEqualTo("100000000.000");
        assertThat(unknown.jsonPath().getString("onChainTreasury")).isNull();
        assertThat(unknown.jsonPath().getBoolean("booksBalanced")).isTrue();
        assertThat(unknown.jsonPath().getString("bookDelta")).isEqualTo("0.000");
        assertThat(unknown.jsonPath().getString("openingTotal")).isEqualTo("1000000.000");
        assertThat(unknown.jsonPath().getString("ledgerTotal")).isEqualTo(booksTotal.toMoney().toPlainString());

        when(chainSvcClient.playTreasury()).thenReturn(new ChainSvcClient.PlayTreasury("0xcoin", booksTotal.mojos()));
        Response agrees = adminReconciliation();
        assertThat(agrees.jsonPath().getString("onChainTreasury")).isEqualTo(booksTotal.toMoney().toPlainString());
        assertThat(agrees.jsonPath().getString("chainDelta")).isEqualTo("0.000");
        assertThat(agrees.jsonPath().getBoolean("chainConsistent")).isTrue();

        // the chain holds 5 000 PLAY less than the books: nothing explains it (no deposit, no withdrawal) -> WARN
        when(chainSvcClient.playTreasury()).thenReturn(new ChainSvcClient.PlayTreasury("0xcoin",
                booksTotal.minus(PlayAmount.ofWholePlay(5_000)).mojos()));
        try (LogCapture log = LogCapture.start()) {
            Response off = adminReconciliation();
            assertThat(off.jsonPath().getBoolean("chainConsistent")).isFalse();
            assertThat(off.jsonPath().getString("chainDelta")).isEqualTo("5000.000");
            assertThat(log.lines()).anySatisfy(l -> assertThat(l).contains("RECONCILIATION MISMATCH"));
        }
        // the numbers need the operator token
        assertThat(given().get("/api/admin/reconciliation").statusCode()).isEqualTo(401);
    }

    // --- Stage C: the public supply block and the withdrawable field ---

    @Test
    void thePublicSupplyNeedsNoSessionAndNeverShowsHerdOnItsOwn() {
        String p = signUp("supply");
        Market sol = listPriced("T_ECOC_SUPPLY", "SOL");
        RoundWindow betting = roundProgress.bettingWindow(sol.id(), HOURLY);
        gameService.placeBet(p, bet(betting, Enums.Direction.UP, 100, "200.000"), betting.startMs() - 1);
        PlayAmount booksTotal = ledger().treasury().plus(ledger().herd()).plus(ledger().playersTotal()).plus(ledger().openStakes());
        when(chainSvcClient.playTreasury()).thenReturn(new ChainSvcClient.PlayTreasury("0xcoinid", booksTotal.mojos()));

        Response r = given().contentType(ContentType.JSON).body("[]").post("/api/rpc/supply");

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.jsonPath().getString("issued")).isEqualTo("100000000.000");
        assertThat(r.jsonPath().getString("treasuryCoinId")).isEqualTo("0xcoinid");
        assertThat(r.jsonPath().getString("onChainTreasury")).isEqualTo(booksTotal.toMoney().toPlainString());
        assertThat(r.jsonPath().getString("heldByPlayers"))
                .isEqualTo(ledger().playersTotal().plus(ledger().openStakes()).toMoney().toPlainString());
        assertThat(r.jsonPath().getString("platformReserve"))
                .isEqualTo(ledger().treasury().plus(ledger().herd()).toMoney().toPlainString());
        assertThat(r.jsonPath().getBoolean("booksBalanced")).isTrue();
        assertThat(r.jsonPath().getBoolean("circuitBreakerOpen")).isFalse();
        // HERD (the platform's revenue, Р-90) is only ever part of the reserve: no field of its own, no fees
        assertThat(r.asString()).doesNotContainIgnoringCase("herd").doesNotContainIgnoringCase("fee");
    }

    @Test
    void myAccountSaysWhatCanBeWithdrawn() {
        String p = signUp("withdrawable");
        assertThat(gameService.account(p).withdrawable()).isEqualTo("0.000");
        gameService.creditOnChainDeposit(p, 1, PlayAmount.ofWholePlay(1_000), "0x" + UUID.randomUUID().toString().replace("-", ""),
                1L, System.currentTimeMillis());
        assertThat(gameService.account(p).withdrawable()).isEqualTo("1000.000");
        assertThat(gameService.account(p).balance()).isEqualTo("11000.000");
    }
}
