# bots — bot strategies as a separate module

Pure, deterministic bot brain for Herd, behind three ports. Spec: [`docs/research/bots_module_design.md`](../docs/research/bots_module_design.md); reasons for its shape: [`architecture_review_2026-10-16.md`](../docs/research/architecture_review_2026-10-16.md) §5; sources of rules and behaviour: [`bot_behavior_sources.md`](../docs/research/bot_behavior_sources.md). First backtest: [`bots_backtest_2026-10-16.md`](../docs/research/bots_backtest_2026-10-16.md).

Depends on `engine` only (stake tiers, fees, liquidation, payout cap are taken from it, not rewritten). No runtime libraries; JUnit 5 and AssertJ in tests. The server calls it since the wiring step (section "Wired into the server" below).

## Layout (`crowdvsmarket.bots`)

| package | content |
|---|---|
| `api` | `MarketSnapshot`, `BarDto`, `BetRequest`/`BetResult`, `BotAccount`, `LastOutcome` |
| `port` | `MarketDataPort`, `BettingPort`, `BotStatePort` |
| `indicator` | `Sma`, `Ema`, `Rsi` (Wilder), `Bollinger`, `Donchian` — own code from the formulas |
| `signal` | `SignalStrategy`, `Signal`, `MarketView`; Momentum, RsiReversion, BollingerBounce, DonchianBreakout, EmaCross, CrowdFollow, CrowdContrarian, Coin |
| `persona` | `Persona`; Disciplined, HouseMoney, LossChaser, Overtrader, Occasional, Maximalist, Cautious |
| `brain` | `BotBrain` (pure), `BotProfile`, `BetIntent`, `Seeds`, `BotRunner` (reads ports, places intents) |
| `catalog` | `catalog.json` (21 bots as data), `Catalog`, `Strategies`, a small JSON reader |
| `backtest` | `Backtester`, `BacktestMain`, `CandleFiles`, `BacktestMarket` |

## Run

```
export JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-oracle
mvn -s ~/.m2/settings-oss.xml -f pom.xml -pl engine,bots -am verify          # tests (also: make test-java)

python3 -I bots/scripts/download_candles.py --days 14                         # candles into .local/bots-candles/
java -cp bots/target/bots.jar:engine/target/engine.jar \
    crowdvsmarket.bots.backtest.BacktestMain .local/bots-candles [--stake 100] [--out report.md]
```

The ports contract test is published as `bots-tests.jar` (classifier `tests`): extend `crowdvsmarket.bots.port.PortsContractTest`, supply the three ports and a seeded bot. The in-memory fake (`InMemoryExchange`, same jar) passes it; a server adapter and an HTTP client must too.

## Design choices where the spec was silent

- **Direction type.** `engine`'s `Direction` and `MarketSpec`/`Interval`/`PlayAmount` are used in the API records (the spec says "public-API terms" and also "depends on `engine`"). `BarDto` prices are `double`; money stays in `PlayAmount`.
- **`MarketSnapshot`** carries the `MarketSpec` and the round length; leverage ceiling by stake tier and fees are derived (`EconomyRules`), not copied.
- **`BotStatePort.account`** returns `Optional` (unknown bot is empty); `BotAccount` also lists the round ids the bot has bet in, so the brain skips them (one bet per round of a market). The server's refusal still comes back as a `BetResult`, never an exception.
- **History.** `MarketView.of` keeps only the trailing run of candles exactly one minute apart; a hole shortens the usable history, and a rule below its warm-up stays silent. The brain asks for 100 candles.
- **Warm-ups.** Momentum n+1; RSI max(3n, n+2) (Wilder seed plus smoothing); Bollinger n; Donchian n+1 (the last candle is not in its own channel); EMA cross 2×slow. EMA is seeded with the SMA of its first n values. A flat series is silent for momentum, Bollinger, EMA and (RSI = 50) RSI.
- **Confidence** (0..1) is a proposal-level scale, only used for reporting; nothing sizes a bet by it.
- **Fallback** when the rule is silent (personas with policy FALLBACK): previous majority if known, else a coin flip from the decision's seed. Persona policies: Disciplined, Occasional, Cautious skip a silent market; the others fall back.
- **Persona numbers** (all marked as assumptions in the classes): Disciplined 85% of rounds, 2 markets, constant stake 100/150/200/250 PLAY chosen by bot id, "realistic mix" leverage copied from the server's `BotStrategy.realisticMix`; HouseMoney: stake x1.5 and leverage +50% per win in a row (max 3) after a win; LossChaser: leverage +50% per loss in a row (max 3) after a loss; Overtrader 95%, 3 markets, minimum stake; Occasional 35%, 1 market; Maximalist 90%, 3 markets, the tier's maximum leverage; Cautious 90%, 3 markets, leverage 2..5, stake 100..200. Stakes are multiples of 10 PLAY (no source; `bot_behavior_sources.md` B7, UNVERIFIED).
- **Market choice** is weighted by `1 + 3 * move / maxMove`, `move` being the relative close change over the last 5 candles (B3 lean towards moving markets; the weight is ours).
- **Seeds.** `hash(botId, marketId, roundId)` is 64-bit FNV-1a over UTF-8 with a zero byte between parts (pinned in `BotBrainTest`). The choice of markets, which spans several, uses `("*", marketId:roundId list)` as its market/round parts. Generator: `SplittableRandom`.
- **Catalog.** 21 bots: 5 price strategies x 3 variants, crowd follow x2, crowd contrarian x2, coin x2; 7 personas x 3 bots, variants of one strategy get different characters. `catalogId` = `<strategy variant>-<persona>[-<persona parameters>]`. The mix is a proposal; change the JSON. After the math review (`bots_math_review_2026-10-16.md` section 5): the persona field takes parameter variants (`loss-chaser@step/cap`, `house-money@step/cap/stakeMultiple`, `maximalist@share`) and the three loss chasers are 0.5/3, 0.75/4, 1.0/5, the three house-money bots 0.5/3/1.5, 0.75/4/1.75, 1.0/5/2.0, one Maximalist is at the full leverage (Coin flipper) and two at half (RSI 2, Donchian 55).
- **Backtest.** One aggregate candle per window for liquidation (the spec says "the window's high/low"); fixed stake 100 PLAY, persona leverage with persona state fed from the replay; no participation sampling and no fallback (a silent window is no bet); crowd strategies are not replayed (no majority in candles). Market terms are the V11 migration's starting values (`BacktestMarket`). Settlement mirrors `GameEngine#resolveRound`.
- **Candle download.** Coinbase 300 candles per call; Hyperliquid serves only about the last 5 000 one-minute candles, so `xyz:` markets get about 3.5 days per run; the script merges into existing files so history accumulates.

## Wired into the server

`server/.../bots/ServerBotPorts` is the adapter of the three ports (it passes `PortsContractTest` from the `bots-tests.jar`, `ServerBotPortsContractTest`); `BotService` runs `BotBrain` through `BotRunner`. Spec of what was built: [`bots_module_design.md`](../docs/research/bots_module_design.md) section 7. Switch: `herd.bots.engine=catalog|legacy` (env `HERD_BOTS_ENGINE`, default `catalog`).

## Payout mode (Р-106)

Each catalog bot has a stable payout-mode preference, part of its strategy: `BotProfile.preferredMode()` is `FIXED` ("Win or lose": 2 x stake or 0, no leverage) or `PERCENTAGE`. `PayoutModes.preferredMode(catalogId, share)` draws it from the catalog id (FNV-1a via `Seeds`, then a `SplittableRandom`), so a restart or a replaced player keeps the bot in the same mode, and a bigger share only adds bots to the fixed side. The share is one setting: env `HERD_BOTS_FIXED_SHARE` (0..1), default 0.5 (`PayoutModes.DEFAULT_FIXED_SHARE`); `CatalogEntry.profile(botId, share)` takes it explicitly. A fixed bot falls back to `PERCENTAGE` on a market whose `MarketSnapshot.odds()` is true (the game refuses the mode on `ODDS` markets). Sizing, timing and direction are the same in both modes; a fixed bet's `BetRequest` carries `mode = FIXED` and the lowest valid leverage (the game ignores it), a percentage bet carries no mode. The balance check of a multi-market pass uses `EconomyRules.fixedFees` for fixed bets. The adapter that fills `MarketSnapshot.odds` and forwards `BetRequest.mode` is in the server (`ServerBotPorts`); the backtest and the population simulation settle every bet in the percentage mode.

## Bets per bot per day after the fixes of the math review

Population simulation (`PopulationMain`, 20 replicates x 4 loops of 14 days of Coinbase 1-minute candles for BTC, ETH, SOL; the same run as the review). The three near-idle bots (Bollinger 20/2.5, RSI 7, Donchian 55) were silent because their rule fires on a few percent of the windows and their personas skip a silent window; they now carry personas that fall back on the crowd, as the other bots do, and the personas moved to bots whose rule always speaks. Bollinger 20/2 (cautious, 176 a day) moved the same way.

| bot (before -> after) | bets per bot-day before | after |
|---|---|---|
| bollinger-20-2.5 (disciplined -> loss chaser 1.0/5) | 37 | 940 |
| rsi-7-25-75 (occasional -> house money 1.0/5/2.0) | 29 | 785 |
| donchian-55 (occasional -> maximalist 0.5) | 14 | 1 052 |
| bollinger-20-2 (cautious -> overtrader) | 176 | 1 475 |

Of the other 17 bots, 14 are within 778-1 419 a day and the three occasional ones (momentum 5, EMA 9/21, crowd contrarian 2) are at about 200 by design (35 % of the rounds, one market). Whole population: 20 395 bets and 18 554 PLAY of fees a day (before 18 122 and 17 165); treasury change +1 439 a day (before +1 016), standard deviation 14 024 (before 17 184); 1.88 retirements a day (before 1.97); liquidated bets 1.8 % (before 4.1 %, the maximalists at half leverage).
