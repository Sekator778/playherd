# Математика ликвидации Hyperliquid (BTC perp) — спецификация для движка

Дата фиксации: 2026-09-25. Все факты о Hyperliquid в разделе 1 — дословные цитаты с URL и датой
фетча (2026-09-25). Раздел 2 — **предложение**: самодостаточная модель, которую реализует наш
Java-движок (движок реализует ровно то, что здесь написано, ничего сверх этого документа).
Раздел 3 — golden vectors, посчитанные `python3`/`decimal`. Раздел 4 — live-read метод для дня 4.
Раздел 5 — сценарии для юнит-тестов, посчитанные из фикстур тем же скриптом/моделью.

Формулы Hyperliquid приведены как есть (английские идентификаторы из доков), пояснения — на
русском.

---

## 1. Что делает Hyperliquid (по докам и живому API)

### 1.1 Источники (все fetched 2026-09-25)

| # | Страница | URL |
|---|---|---|
| 1 | Liquidations | https://hyperliquid.gitbook.io/hyperliquid-docs/trading/liquidations.md |
| 2 | Margining | https://hyperliquid.gitbook.io/hyperliquid-docs/trading/margining.md |
| 3 | Margin tiers | https://hyperliquid.gitbook.io/hyperliquid-docs/trading/margin-tiers.md |
| 4 | Contract specifications | https://hyperliquid.gitbook.io/hyperliquid-docs/trading/contract-specifications.md |
| 5 | Robust price indices | https://hyperliquid.gitbook.io/hyperliquid-docs/trading/robust-price-indices.md |
| 6 | Oracle | https://hyperliquid.gitbook.io/hyperliquid-docs/hypercore/oracle.md |
| 7 | Funding | https://hyperliquid.gitbook.io/hyperliquid-docs/trading/funding.md |
| 8 | Info endpoint (candle snapshot + cap) | https://hyperliquid.gitbook.io/hyperliquid-docs/for-developers/api/info-endpoint.md |

Все страницы доступны с суффиксом `.md` (даёт сырой markdown вместо отрендеренного HTML —
подтверждено HTTP 200 на все восемь URL, 2026-09-25). Пути 1–6 отличаются от пути, ранее
процитированного в проекте (`.../hypercore/oracle.md` — подтверждён, актуален), остальные
проверены заново сегодня, а не унаследованы из более раннего research.

### 1.2 Когда происходит ликвидация — overview (liquidations.md, дословно)

> A liquidation event occurs when a trader's positions move against them to the point where the
> account equity falls below the maintenance margin. The maintenance margin is half of the initial
> margin at max leverage, which varies from 3-40x. In other words, the maintenance margin is between
> 1.25% (for 40x max leverage assets) and 16.7% (for 3x max leverage assets) depending on the asset.

Для BTC (max leverage 40x, подтверждено live API — см. 1.8) maintenance margin rate = **1.25%**.

> When the account equity drops below maintenance margin, the positions are first attempted to be
> entirely closed by sending market orders to the book. [...] If the positions are entirely or
> partially closed such that the maintenance margin requirements are met, any remaining collateral
> remains with the trader.
>
> If the account equity drops below 2/3 of the maintenance margin without successful liquidation
> through the book, a backstop liquidation happens through the liquidator vault.

### 1.3 Isolated vs cross (margining.md, дословно)

> *Cross margin* is the default, which allows for maximal capital efficiency by sharing collateral
> between all other cross margin positions. *Isolated margin* is also supported, which allows an
> asset's collateral to be constrained to that asset. Liquidations in that asset do not affect other
> isolated positions or cross positions.

> The margin required to open a position is `position_size * mark_price / leverage`. [...] Isolated
> positions support adding and removing margin after opening the position.

> Cross positions are liquidated when the account value (including unrealized pnl) is less than the
> *maintenance margin* times the total open notional position. [...] Isolated positions are
> liquidated by the same maintenance margin logic, but the only inputs to the computation are the
> isolated margin and the notional value of the isolated position.

Наш движок реализует **только isolated** (см. §2.1, обоснование).

### 1.4 Maintenance margin и margin tiers для BTC (margin-tiers.md, дословно)

> `maintenance_margin = notional_position_value * maintenance_margin_rate - maintenance_deduction`
>
> `maintenance_margin_rate(tier = n) = (Initial Margin Rate at Maximum leverage at tier n) / 2`
>
> `maintenance_deduction(tier = 0) = 0`
>
> `maintenance_deduction(tier = n) = maintenance_deduction(tier = n - 1) + notional_position_lower_bound(tier = n) * (maintenance_margin_rate(tier = n) - maintenance_margin_rate(tier = n - 1))` for `n > 0`

BTC margin tiers (mainnet, из той же страницы):

| Notional Position Value (USDC) | Max Leverage |
|---|---|
| 0–150M | 40 |
| >150M | 20 |

Отсюда для BTC:

- tier0: `maintenance_margin_rate = (1/40)/2 = 0.0125` (1.25%), `maintenance_deduction = 0`.
- tier1 (>150M): `maintenance_margin_rate = (1/20)/2 = 0.025` (2.5%),
  `maintenance_deduction = 0 + 150_000_000 * (0.025 - 0.0125) = 1_875_000`.

Подтверждено живым API (см. 1.8) — `marginTableId=56` для BTC, таблица тиров идентична докам.

### 1.5 Какая цена триггерит ликвидацию — mark price (robust-price-indices.md, liquidations.md)

> Liquidations use the mark price, which combines external CEX prices with Hyperliquid's book
> state. This makes liquidations more robust than using a single instantaneous book price.
> (liquidations.md)

> *Mark price* is the median of the following prices:
> 1. Oracle price plus a 150 second exponential moving average (EMA) of the difference between
>    Hyperliquid's mid price and the oracle price
> 2. The median of best bid, best ask, last trade on Hyperliquid
> 3. Median of Binance, OKX, Bybit, Gate IO, MEXC perp mid prices with weights 3, 2, 2, 1, 1,
>    respectively
>
> If exactly two out of the three inputs above exist, the 30 second EMA of the median of best bid,
> best ask, and last trade on Hyperliquid is also added to the median inputs.
>
> Mark price is an unbiased and robust estimate of the fair perp price, and is used for margining,
> liquidations, triggering TP/SL, and computing unrealized pnl. Mark price is updated whenever
> validators publish new oracle prices [...] approximately once every 3 seconds.
> (robust-price-indices.md)

Отдельно *oracle price* (hypercore/oracle.md, дословно):

> The validators are responsible for publishing spot oracle prices for each perp asset every 3
> seconds. [...] The spot oracle prices are computed by each validator as the weighted median of
> Binance, OKX, Bybit, Kraken, Kucoin, Gate IO, MEXC, and Hyperliquid spot mid prices for each
> asset, with weights 3, 2, 2, 1, 1, 1, 1, 1 respectively. [...] Perps on assets that have primary
> spot liquidity outside of Hyperliquid (e.g. BTC) do not include Hyperliquid spot prices in the
> oracle.

Oracle price используется для funding и как один из трёх входов mark price; **ликвидации, margining
и unrealized PnL используют mark price, не oracle price напрямую**.

### 1.6 Формула liquidation price (liquidations.md, дословно)

> The precise formula for the liquidation price of a position is
>
> `liq_price = price - side * margin_available / position_size / (1 - l * side)`
>
> where
>
> `l = 1 / MAINTENANCE_LEVERAGE`. For assets with margin tiers, maintenance leverage depends on the
> unique margin tier corresponding to the position value at the liquidation price.
>
> `side = 1 for long and -1 for short`
>
> `margin_available (cross) = account_value - maintenance_margin_required`
>
> `margin_available (isolated) = isolated_margin - maintenance_margin_required`

> Once a position is opened, a liquidation price is shown. This price has the certainty of the
> entry price, but still may not be the actual liquidation price due to funding payments or changes
> in unrealized pnl in other positions (for cross margin positions). [...] The liquidation price
> does depend on leverage set for isolated margin positions, because the amount of isolated margin
> allocated depends on the initial margin set.

Переменные (наша формализация терминов из докa, для избежания двусмысленности):

- `price` — цена, от которой считается формула; для isolated-позиции, «зафиксированной на
  entry price» — это entry price позиции.
- `position_size` — размер позиции в базовом активе (BTC), положительное число.
- `side` — `+1` для long, `-1` для short.
- `l` — maintenance margin rate тира, которому соответствует notional позиции на цене
  ликвидации (для BTC tier0 при разумных для демо размерах позиции — 0.0125, см. §1.4).
- `isolated_margin` — маржа, выделенная под изолированную позицию при открытии:
  `position_size * entry_price / leverage`.
- `maintenance_margin_required` — `notional_position_value * l - maintenance_deduction`.

### 1.7 Что происходит с остатком маржи при ликвидации (liquidations.md, дословно)

> When the account equity drops below maintenance margin, the positions are first attempted to be
> entirely closed by sending market orders to the book. The orders are for the full size of the
> position, and may be fully or partially closed. If the positions are entirely or partially closed
> such that the maintenance margin requirements are met, any remaining collateral remains with the
> trader.
>
> If the account equity drops below 2/3 of the maintenance margin without successful liquidation
> through the book, a backstop liquidation happens through the liquidator vault. [...]
>
> When an isolated position is backstop liquidated, that isolated position and isolated margin are
> transferred to the liquidator. The user's cross margin and positions are untouched.
>
> During backstop liquidation, the maintenance margin is not returned to the user. This is because
> the liquidator vault requires a buffer to make sure backstop liquidations are profitable on
> average.

> As described above, the majority of liquidations on Hyperliquid are sent directly to the order
> book. This allows all users to compete for the liquidation flow, and allows the liquidated user to
> keep any remaining margin. Unlike CEXs there is no clearance fee on liquidations.

Частичная ликвидация (там же, дословно):

> For liquidatable positions larger than 100k USDC (10k USDC on testnet for easier testing), only
> 20% of the position will be sent as a market liquidation order to the book. After a block where
> any position of a user is partially liquidated, there is a cooldown period of 30 seconds. During
> this cooldown period, all market liquidation orders for that user will be for the entire position.

**Итог**: типичный (order-book) сценарий — трейдер теряет всё сверх maintenance margin, но
maintenance margin остаётся у него. Backstop-сценарий (equity < 2/3 maintenance margin, ликвидатор
не смог закрыть через стакан) — трейдер теряет изолированную маржу целиком.

### 1.8 Contract specifications (для BTC, дословно, contract-specifications.md)

| Поле | Значение |
|---|---|
| Instrument type | Linear perpetual |
| Initial margin fraction | `1 / (leverage set by user)` |
| Maintenance margin fraction | `Half of maximum initial margin fraction` |
| Delivery / expiration | N/A (funding payments every hour) |
| Funding impact notional | 20000 USDC for BTC and ETH |
| Maximum market order value | «$30,000,000 for max leverage >= 25» (BTC max leverage = 40 → применим верхний порог) |

### 1.9 Fees и funding

Комиссия за ликвидацию (liquidations.md, дословно, уже процитировано в §1.7): **«Unlike CEXs there
is no clearance fee on liquidations.»**

Funding (funding.md, дословно):

> The funding rate is a periodic fee that is paid by one side of the contract (either long or
> short) to the other side. Funding is purely peer-to-peer and no fees are collected on the
> payments.
>
> For consistency with CEXs, interest rate component is predetermined at 0.01% every 8 hours,
> which is 0.00125% every hour, or 11.6% APR paid to short.
>
> The funding rate on Hyperliquid is paid every hour.
>
> `Funding Rate (F) = Average Premium Index (P) + clamp (interest rate - Premium Index (P), -0.0005, 0.0005)`
>
> Funding on Hyperliquid is capped at 4%/hour.
>
> the funding payment at the end of the interval is `position_size * oracle_price * funding_rate`.
> In particular, the spot oracle price is used to convert the position size to notional value,
> *not the mark price.*

Trading-fee (maker/taker) на открытие/закрытие позиции по ордеру не документирован ни на одной из
шести зафетченных страниц (это отдельная страница `fees.md`/`fee-schedule.md`, не входившая в
список задачи) — помечаю как открытый вопрос в §6, не блокирует MVP (см. §2.5, предложение —
fees=0 в MVP).

### 1.10 Живой API (read-only, 2026-09-25, ~14:10 UTC)

**Запрос 1** — статические параметры маржи/плеча:

```
POST https://api.hyperliquid.xyz/info
Content-Type: application/json

{"type":"meta"}
```

Релевантные фрагменты ответа (BTC — первый элемент `universe`, index 0):

```json
{"szDecimals": 5, "name": "BTC", "maxLeverage": 40, "marginTableId": 56}
```

```json
[56, {
  "description": "tiered 40x",
  "marginTiers": [
    {"lowerBound": "0.0", "maxLeverage": 40},
    {"lowerBound": "150000000.0", "maxLeverage": 20}
  ]
}]
```

Совпадает дословно с margin-tiers.md (§1.4) — `maxLeverage=40` для BTC, тир-граница 150M USDC.
`szDecimals=5` — BTC-позиция округляется до 5 знаков после запятой (0.00001 BTC); использовано в
§2.6 для решения по scale.

**Запрос 2** — текущие oracle/mark price (метод для дня 4, дублирован в §4):

```
POST https://api.hyperliquid.xyz/info
Content-Type: application/json

{"type":"metaAndAssetCtxs"}
```

Релевантный фрагмент ответа (BTC ctx, тот же index 0, что и в `universe`):

```json
{
  "funding": "0.0000125",
  "openInterest": "38634.58326",
  "prevDayPx": "84215.0",
  "dayNtlVlm": "3748229296.1374282837",
  "premium": "-0.0002036123",
  "oraclePx": "83492.0",
  "markPx": "83468.0",
  "midPx": "83474.5",
  "impactPxs": ["83467.9", "83475.0"],
  "dayBaseVlm": "44459.32478"
}
```

`funding=0.0000125` — часовая ставка (0.00125%), близко к чистой interest-rate компоненте
(0.00125%/ч по formula funding.md §1.9), что означает premium ≈ 0 на момент фетча — согласуется с
`premium: "-0.0002036123"` (малое отклонение от нуля).

---

## 2. Модель для нашего движка (предложение)

Каждое решение ниже — **предложение** с однострочным обоснованием, подлежит пересмотру в неделю 1
по правилу `the project plan`.

### 2.1 Скоуп

**Решение**: только isolated margin, целочисленное плечо 2..40, только BTC tier0 (notional
позиции < 150M USDC).
**Обоснование**: MVP — toy-money демо на малых размерах позиций (см. §2.7 «стандартный размер»),
tier1 недостижим ни при каком реалистичном сценарии демо; cross margin требует моделировать
портфель из нескольких позиций одновременно, что не входит в MVP (`the project plan` §2 — один раунд
«BTC вверх/вниз» на позицию).

### 2.2 Формулы (BTC, tier0: `l = 0.0125`, `maintenance_deduction = 0`)

Обозначения: `Q` — размер позиции в BTC, `P0` — entry price, `L` — целочисленное плечо (2..40),
`side = +1` (long) / `-1` (short), `P` — текущая/цена закрытия.

```
initial_margin (isolated_margin) = round2( Q * P0 / L )

maintenance_margin_rate l = 1 / (2 * maxLeverageTier)              # BTC tier0: l = 1/80 = 0.0125
maintenance_margin_required = round2( Q * P0 * l )                  # см. вывод ниже

liquidation_price = round2( (Q*P0 - side*isolated_margin) / (Q*(1 - l*side)) )

unrealized_pnl(P) = round2( side * Q * (P - P0) )

payout_at_round_close(P_close) = round2( max(0, isolated_margin + unrealized_pnl(P_close)) )

payout_on_liquidation = 0                                           # продуктовое решение D-7
```

**Вывод `liquidation_price`** (что это формула HL из §1.6, а не наша отдельная):
подставляя `margin_available (isolated) = isolated_margin - maintenance_margin_required`,
`maintenance_margin_required = Q*P0*l` (нотионал на entry, tier0, `deduction=0`) и `price = P0` в
формулу HL `liq_price = price - side*margin_available/position_size/(1-l*side)`, после сокращения
членов `-P0*Q*l*side + side*Q*P0*l = 0` формула точно сводится к
`liq_price = (Q*P0 - side*isolated_margin) / (Q*(1 - l*side))`. Эта же формула получается прямым
решением уравнения `isolated_margin + side*Q*(liq_price - P0) = Q*liq_price*l` (equity на цене
ликвидации равно maintenance margin **на этой же цене**, т.е. динамическому, не entry-based,
maintenance margin) — то есть оба прочтения формулы HL дают идентичный результат для tier0
(проверено алгебраически и численно в скрипте §3). Значит наша формула не приближение, а точная
реализация формулы HL при отсутствии пересечения тиров.

**`payout_on_liquidation = 0`** — продуктовое решение **D-7** (`the decision log`, D-7,
2026-09-25, Алекс): при ликвидации игрок теряет всю ставку (всю изолированную маржу), повторно
токены не выдаются. Это решение задаёт именно *payout*, не *цену* ликвидации — `liquidation_price`
остаётся точной реализацией формулы Hyperliquid (вывод выше) без изменений. Оно осознанно
расходится с тем, что реально происходит на самой Hyperliquid при order-book-ликвидации (§1.7,
дословно: «majority of liquidations» идут через стакан, и «any remaining collateral remains with
the trader» — на цене ровно `liquidation_price` equity по построению равно
`maintenance_margin_required`, то есть реальный трейдер на HL обычно получает обратно эту сумму, не
теряет всё); наш движок этот остаток трейдеру не возвращает. HL-модель backstop-ликвидации (equity
< 2/3 maintenance margin — полная потеря, §1.7) по-прежнему не моделируется отдельно — D-7 просто
делает потерю полной во всех случаях ликвидации, а не только в backstop-случае.

### 2.3 Определение ликвидации по OHLC-свечам

**Решение**:
- **Long** ликвидируется внутри свечи, если `candle.low <= liquidation_price`.
- **Short** ликвидируется внутри свечи, если `candle.high >= liquidation_price`.

**Обоснование**: у нас нет тикового потока/mark-price временного ряда за прошлые периоды через
использованные read-only эндпоинты (см. §2.4) — экстремумы свечи (low/high) единственный доступный
прокси внутрисвечного движения цены.

**Правило «ликвидация и закрытие раунда в одной свече»**: если свеча — одновременно последняя свеча
раунда (закрытие) и в этой же свече `low <= liquidation_price` (long) / `high >= liquidation_price`
(short), **приоритет у ликвидации** — позиция считается ликвидированной, `payout_at_round_close` не
применяется, применяется `payout_on_liquidation`.
**Обоснование**: у нас нет внутрисвечного порядка событий (не знаем, наступил ли `low`/`high` до
или после момента закрытия раунда) — берём консервативный (для трейдера — худший) исход, чтобы не
переплачивать при неопределённости, и это единственный вариант, который не требует
внутрисвечных данных, которых у нас нет.

**Правило «свеча пробивает `liquidation_price` гэпом»** (например, `open` уже хуже
`liquidation_price` для long, без плавного прохождения): правило `low <= liquidation_price`
автоматически покрывает этот случай, так как `low <= open <= liquidation_price` в этой ситуации.
Отдельно: **исполнение всегда считается по `liquidation_price`**, а не по фактической
цене гэпа (`open` или хуже) — то есть `payout_on_liquidation` не зависит от того, насколько сильно
цена прошла свечу мимо уровня. **Обоснование**: без книги ордеров/тикового потока у нас нет
основания для более точной цены исполнения; фиксированная `payout_on_liquidation` (§2.2) — простое,
детерминированное и тестируемое допущение, которое не пытается угадывать глубину гэпа.

### 2.4 Какая из наших цен стоит вместо mark price в историческом реплее

**Решение**: OHLC свечей `candleSnapshot` (см. `tools/fetch_hl_candles.py`,
`engine/src/test/resources/fixtures/`) — `open` как entry/close price раунда, `low`/`high` как
триггеры ликвидации внутри свечи.
**Обоснование**: исторический временной ряд именно `mark price` не публикуется отдельно ни на
одной из зафетченных read-only страниц/эндпоинтов (§1); `candleSnapshot` — единственный документированный
источник исторических OHLC для BTC. Свечи Hyperliquid отражают реальную торговую цену на самой
Hyperliquid, которая — один из трёх компонентов формулы mark price (§1.5, пункт 2), поэтому
статистически близка к mark price, хотя не идентична ей по формуле. Для live-режима (день 4 и
далее) вместо этого читаем `markPx`/`oraclePx` напрямую (§4) — там расхождение с этим упрощением не
возникает.

### 2.5 Fees и funding в MVP

**Решение**: `fees = 0`, `funding = 0` в MVP.
**Обоснование**: (a) на ликвидациях комиссии нет и на реальном HL (§1.7, дословно — «no clearance
fee on liquidations»); (b) trading fee (maker/taker) не задокументирован на зафетченных сегодня
страницах (см. §1.9, открытый вопрос §6) — нечего реализовывать без числа из источника; (c) funding
начисляется **почасово** (§1.9) и зависит от **oracle price**, которого исторически для каждого
часа у нас нет отдельным полем (только OHLC) — реализация потребовала бы отдельного источника
часовых oracle-цен, не входящего в скоуп недели 1. Раунды в MVP — одна открытая/закрытая позиция за
раунд (дневной или часовой), не несущая перманентного funding-цикла как на реальной бирже.

### 2.6 BigDecimal precision/rounding

| Величина | Scale | RoundingMode | Обоснование |
|---|---|---|---|
| Цена (entry/liq/OHLC) | 2 знака (USD-центы) | `HALF_UP` | Стандартная точность для USD-номинала; входные данные API (`"83451.0"`) имеют ≤2 знаков, 2dp не теряет точность источника |
| Размер позиции (BTC) | 5 знаков | `HALF_UP` | Совпадает с `szDecimals=5` для BTC, подтверждено live `meta` (§1.10) — реалистичная точность реальной биржи |
| Маржа/PnL/payout (USD) | 2 знака | `HALF_UP` | Toy-money в USD-эквиваленте, центовая точность стандартна для денежных сумм |
| Промежуточные деления (`Q*P0/L`, формула `liq_price`) | `MathContext.DECIMAL128` (34 значащих цифры) | — | Избегает `ArithmeticException` на непериодических дробях (например, `1/3`); округление до целевого scale применяется только к финальному результату каждой формулы, не к промежуточным шагам |
| Порядок округления в `liquidation_price` | сначала округлить `isolated_margin` до 2dp, потом посчитать `liquidation_price` из округлённого `isolated_margin` | — | `isolated_margin` — реальный, отображаемый пользователю баланс (уже округлённый), а не абстрактное число; `liquidation_price` должна быть выведена из фактически внесённой маржи, а не из идеализированного нецелого значения |

Golden vectors в §3 посчитаны питоновским `decimal` по этим же правилам (`ROUND_HALF_UP`,
`quantize`) — числа воспроизводимы Java-движком через `BigDecimal.setScale(n, RoundingMode.HALF_UP)`
1:1.

### 2.7 Стандартный размер позиции

**Решение**: `STANDARD_SIZE_BTC = 0.10000` BTC для всех golden vectors (§3) и всех сценариев (§5).
**Обоснование**: диапазон цен в нашей дневной фикстуре (`engine/src/test/resources/fixtures/hl_btc_1d.csv`,
проверено по всем 2228 строкам) — минимум `low` 9808.6 (2020-09-05), максимум `high` 126297.0
(2025-10-06), то есть $9.8K–$126.3K, а не ранее заявленные здесь $11.6K–$87K. При
`STANDARD_SIZE_BTC=0.10000` это даёт notional $980.86–$12,629.70 и isolated margin (leverage 40 на
нижней границе цены / leverage 2 на верхней) от $24.52 до $6,314.85 в зависимости от плеча —
по-прежнему интуитивный демо-диапазон игрушечных денег, не задевает tier1 (§2.1).

---

## 3. Golden vectors

Скрипт (python3, только stdlib `decimal`) реализует ровно формулы §2.2/§2.6:

```python
#!/usr/bin/env python3
"""Reference implementation (python3 decimal) of the engine model proposed in
docs/research/hl_liquidation_math.md section (b). Produces:
  1. Golden vectors (long/short x leverage 2,3,10,25,40 x two entry prices).
  2. Historical liquidation scenarios computed from the fetched candle fixtures.

This mirrors, field for field, what the Java engine is expected to implement:
  - maintenance margin rate l = 1 / (2 * maxLeverageTier)  [BTC tier0: maxLeverage=40 -> l=0.0125]
  - isolated_margin = round(size * entry_price / leverage, 2, HALF_UP)
  - liq_price = (size*entry_price - side*isolated_margin) / (size*(1 - l*side)), side=+1 long/-1 short
    rounded to 2dp HALF_UP
  - unrealized_pnl(P) = side * size * (P - entry_price), rounded 2dp HALF_UP
  - payout_at_round_close(P) = max(0, isolated_margin + unrealized_pnl(P)), rounded 2dp
  - payout_on_liquidation = 0 (product decision D-7, the decision log: the player loses
    the entire stake on liquidation), see doc section 2.2
  - long liquidated in a candle iff candle.low <= liq_price_long
  - short liquidated in a candle iff candle.high >= liq_price_short
"""
from decimal import Decimal, ROUND_HALF_UP, getcontext
import csv
import json

getcontext().prec = 50  # generous internal precision; final values rounded explicitly

TWO_DP = Decimal("0.01")
FIVE_DP = Decimal("0.00001")

BTC_MAX_LEVERAGE_TIER0 = Decimal(40)          # from live meta: universe[BTC].maxLeverage, marginTableId=56 tier0
MAINTENANCE_MARGIN_RATE = Decimal(1) / (Decimal(2) * BTC_MAX_LEVERAGE_TIER0)  # l = 0.0125

STANDARD_SIZE_BTC = Decimal("0.10000")  # engine-wide standard demo position size


def q2(x: Decimal) -> Decimal:
    return x.quantize(TWO_DP, rounding=ROUND_HALF_UP)


def q5(x: Decimal) -> Decimal:
    return x.quantize(FIVE_DP, rounding=ROUND_HALF_UP)


def isolated_margin(size: Decimal, entry_price: Decimal, leverage: int) -> Decimal:
    return q2(size * entry_price / Decimal(leverage))


def liquidation_price(size: Decimal, entry_price: Decimal, leverage: int, side: int) -> Decimal:
    im = isolated_margin(size, entry_price, leverage)
    side_d = Decimal(side)
    l = MAINTENANCE_MARGIN_RATE
    numerator = size * entry_price - side_d * im
    denominator = size * (Decimal(1) - l * side_d)
    return q2(numerator / denominator)


def unrealized_pnl(size: Decimal, entry_price: Decimal, side: int, price: Decimal) -> Decimal:
    return q2(Decimal(side) * size * (price - entry_price))


def maintenance_margin_required(size: Decimal, entry_price: Decimal) -> Decimal:
    """Maintenance margin at entry notional, BTC tier0 (deduction=0): size*entry_price*l."""
    return q2(size * entry_price * MAINTENANCE_MARGIN_RATE)


def payout_on_liquidation(size: Decimal, entry_price: Decimal) -> Decimal:
    """Product decision D-7 (the decision log, D-7, 2026-09-25, Алекс): on
    liquidation the player loses the entire stake (the whole isolated margin) —
    payout is always 0, regardless of size/entry_price. This diverges from what HL
    itself does at a clean order-book liquidation (any remaining collateral stays
    with the trader there, see doc section 1.7/2.2) — an explicit product choice,
    not a further approximation of HL's math. liquidation_price itself is unaffected."""
    del size, entry_price  # unused: payout is a flat 0 under D-7, not a function of them
    return Decimal("0.00")


def payout_at_close(size: Decimal, entry_price: Decimal, leverage: int, side: int, close_price: Decimal) -> Decimal:
    im = isolated_margin(size, entry_price, leverage)
    pnl = unrealized_pnl(size, entry_price, side, close_price)
    return max(Decimal("0.00"), q2(im + pnl))


# ---------------------------------------------------------------------------
# 1. Golden vectors
# ---------------------------------------------------------------------------

def golden_vectors():
    # close_price is a stated, arbitrary hypothetical round-close price per entry_price (not
    # itself an HL fact) — only used to give unrealized_pnl/payout_at_round_close something
    # concrete to compute against; chosen to move away from entry in both directions across
    # the two groups so payout_at_round_close exercises both the positive and the max(0, ...)
    # clamped-to-zero case.
    entry_prices_and_close = [
        (Decimal("83451.00"), Decimal("90000.00")),
        (Decimal("50000.00"), Decimal("45000.00")),
    ]
    leverages = [2, 3, 10, 25, 40]
    sides = [("long", 1), ("short", -1)]
    rows = []
    for entry, close_price in entry_prices_and_close:
        for side_name, side in sides:
            for lev in leverages:
                im = isolated_margin(STANDARD_SIZE_BTC, entry, lev)
                liq = liquidation_price(STANDARD_SIZE_BTC, entry, lev, side)
                mm = maintenance_margin_required(STANDARD_SIZE_BTC, entry)
                payout_liq = payout_on_liquidation(STANDARD_SIZE_BTC, entry)
                pnl = unrealized_pnl(STANDARD_SIZE_BTC, entry, side, close_price)
                payout_close = payout_at_close(STANDARD_SIZE_BTC, entry, lev, side, close_price)
                rows.append(
                    {
                        "side": side_name,
                        "entry_price": str(entry),
                        "leverage": lev,
                        "size_btc": str(STANDARD_SIZE_BTC),
                        "isolated_margin_usd": str(im),
                        "liquidation_price": str(liq),
                        "maintenance_margin_usd": str(mm),
                        "maintenance_margin_rate": str(MAINTENANCE_MARGIN_RATE),
                        "payout_on_liquidation_usd": str(payout_liq),
                        "close_price": str(close_price),
                        "unrealized_pnl_usd": str(pnl),
                        "payout_at_round_close_usd": str(payout_close),
                    }
                )
    return rows


# ---------------------------------------------------------------------------
# 2. Historical scenarios
# ---------------------------------------------------------------------------

def load_csv(path):
    rows = []
    with open(path, newline="", encoding="utf-8") as f:
        for r in csv.DictReader(f):
            rows.append(
                {
                    "open_time_ms": int(r["open_time_ms"]),
                    "open": Decimal(r["open"]),
                    "high": Decimal(r["high"]),
                    "low": Decimal(r["low"]),
                    "close": Decimal(r["close"]),
                }
            )
    return rows


def liquidated_leverages(open_price, low, high):
    long_liq = []
    short_liq = []
    for lev in range(2, 41):
        liq_long = liquidation_price(STANDARD_SIZE_BTC, open_price, lev, 1)
        if low <= liq_long:
            long_liq.append(lev)
        liq_short = liquidation_price(STANDARD_SIZE_BTC, open_price, lev, -1)
        if high >= liq_short:
            short_liq.append(lev)
    return long_liq, short_liq


def iso_utc(ms):
    import datetime
    return datetime.datetime.fromtimestamp(ms / 1000, datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def scenario_row(candle, fixture_file):
    open_price, low, high = candle["open"], candle["low"], candle["high"]
    long_liq, short_liq = liquidated_leverages(open_price, low, high)
    return {
        "fixtureFile": fixture_file,
        "candleOpenTimeMs": str(candle["open_time_ms"]),
        "utc": iso_utc(candle["open_time_ms"]),
        "open": str(open_price),
        "high": str(high),
        "low": str(low),
        "close": str(candle["close"]),
        "liquidatedLongLeverages": long_liq,
        "liquidatedShortLeverages": short_liq,
    }


def top_scenarios(daily_path, hourly_path):
    daily = load_csv(daily_path)
    hourly = load_csv(hourly_path)

    drops = sorted(daily, key=lambda c: (c["open"] - c["low"]) / c["open"], reverse=True)[:10]
    rises = sorted(daily, key=lambda c: (c["high"] - c["open"]) / c["open"], reverse=True)[:10]

    def extremity(c):
        down = (c["open"] - c["low"]) / c["open"]
        up = (c["high"] - c["open"]) / c["open"]
        return max(down, up)

    hourly_extreme = sorted(hourly, key=extremity, reverse=True)[:5]

    out = {
        "top10_daily_drops": [scenario_row(c, "hl_btc_1d.csv") for c in drops],
        "top10_daily_rises": [scenario_row(c, "hl_btc_1d.csv") for c in rises],
        "top5_hourly_extreme": [scenario_row(c, "hl_btc_1h.csv") for c in hourly_extreme],
    }
    return out


if __name__ == "__main__":
    import sys

    daily_path = sys.argv[1] if len(sys.argv) > 1 else "hl_btc_1d.csv"
    hourly_path = sys.argv[2] if len(sys.argv) > 2 else "hl_btc_1h.csv"

    print("=== GOLDEN VECTORS ===")
    gv = golden_vectors()
    print(json.dumps(gv, indent=2))

    print("\n=== HISTORICAL SCENARIOS ===")
    scenarios = top_scenarios(daily_path, hourly_path)
    print(json.dumps(scenarios, indent=2))
```

Запуск: `python3 hl_engine_model.py engine/src/test/resources/fixtures/hl_btc_1d.csv engine/src/test/resources/fixtures/hl_btc_1h.csv`.

Вывод (golden vectors, 20 строк — long/short × leverage {2,3,10,25,40} × entry price
{83451.00 (последняя реальная дневная close-цена из `hl_btc_1d.csv`), 50000.00 (круглое число для
ручной проверки)}, `size_btc=0.10000` для всех строк). `payout_on_liquidation_usd` — 0.00 для всех
строк (продуктовое решение D-7, см. §2.2). `close_price` — стейтед гипотетическая цена закрытия
раунда (не факт HL, а параметр этой таблицы, взят per entry_price: 90000.00 для группы 83451.00,
45000.00 для группы 50000.00, чтобы показать оба случая `payout_at_round_close` — и положительный, и
обнулённый `max(0, ...)`); `unrealized_pnl_usd`/`payout_at_round_close_usd` посчитаны на этой цене:

| side | entry_price | leverage | size_btc | isolated_margin_usd | liquidation_price | maintenance_margin_usd | payout_on_liquidation_usd | close_price | unrealized_pnl_usd | payout_at_round_close_usd |
|---|---|---|---|---|---|---|---|---|---|---|
| long | 83451.00 | 2 | 0.10000 | 4172.55 | 42253.67 | 104.31 | 0.00 | 90000.00 | 654.90 | 4827.45 |
| long | 83451.00 | 3 | 0.10000 | 2781.70 | 56338.23 | 104.31 | 0.00 | 90000.00 | 654.90 | 3436.60 |
| long | 83451.00 | 10 | 0.10000 | 834.51 | 76056.61 | 104.31 | 0.00 | 90000.00 | 654.90 | 1489.41 |
| long | 83451.00 | 25 | 0.10000 | 333.80 | 81127.09 | 104.31 | 0.00 | 90000.00 | 654.90 | 988.70 |
| long | 83451.00 | 40 | 0.10000 | 208.63 | 82394.63 | 104.31 | 0.00 | 90000.00 | 654.90 | 863.53 |
| short | 83451.00 | 2 | 0.10000 | 4172.55 | 123631.11 | 104.31 | 0.00 | 90000.00 | -654.90 | 3517.65 |
| short | 83451.00 | 3 | 0.10000 | 2781.70 | 109894.32 | 104.31 | 0.00 | 90000.00 | -654.90 | 2126.80 |
| short | 83451.00 | 10 | 0.10000 | 834.51 | 90662.81 | 104.31 | 0.00 | 90000.00 | -654.90 | 179.61 |
| short | 83451.00 | 25 | 0.10000 | 333.80 | 85717.53 | 104.31 | 0.00 | 90000.00 | -654.90 | 0.00 |
| short | 83451.00 | 40 | 0.10000 | 208.63 | 84481.28 | 104.31 | 0.00 | 90000.00 | -654.90 | 0.00 |
| long | 50000.00 | 2 | 0.10000 | 2500.00 | 25316.46 | 62.50 | 0.00 | 45000.00 | -500.00 | 2000.00 |
| long | 50000.00 | 3 | 0.10000 | 1666.67 | 33755.24 | 62.50 | 0.00 | 45000.00 | -500.00 | 1166.67 |
| long | 50000.00 | 10 | 0.10000 | 500.00 | 45569.62 | 62.50 | 0.00 | 45000.00 | -500.00 | 0.00 |
| long | 50000.00 | 25 | 0.10000 | 200.00 | 48607.59 | 62.50 | 0.00 | 45000.00 | -500.00 | 0.00 |
| long | 50000.00 | 40 | 0.10000 | 125.00 | 49367.09 | 62.50 | 0.00 | 45000.00 | -500.00 | 0.00 |
| short | 50000.00 | 2 | 0.10000 | 2500.00 | 74074.07 | 62.50 | 0.00 | 45000.00 | 500.00 | 3000.00 |
| short | 50000.00 | 3 | 0.10000 | 1666.67 | 65843.65 | 62.50 | 0.00 | 45000.00 | 500.00 | 2166.67 |
| short | 50000.00 | 10 | 0.10000 | 500.00 | 54320.99 | 62.50 | 0.00 | 45000.00 | 500.00 | 1000.00 |
| short | 50000.00 | 25 | 0.10000 | 200.00 | 51358.02 | 62.50 | 0.00 | 45000.00 | 500.00 | 700.00 |
| short | 50000.00 | 40 | 0.10000 | 125.00 | 50617.28 | 62.50 | 0.00 | 45000.00 | 500.00 | 625.00 |

Наблюдение-проверка: `maintenance_margin_usd` не зависит от leverage при фиксированной
`entry_price` (согласуется с §1.3/§1.8 — maintenance margin rate не зависит от плеча, выбранного
пользователем, только от max leverage тира актива). `payout_at_round_close_usd` обнуляется
(`max(0, ...)`) там, где убыток превышает isolated margin (высокое плечо, цена против трейдера) —
short/83451/leverage{25,40} и long/50000/leverage{10,25,40} в этой таблице.

---

## 4. Live-read метод для BTC oracle/mark price (день 4)

**Запрос**:

```
POST https://api.hyperliquid.xyz/info
Content-Type: application/json

{"type":"metaAndAssetCtxs"}
```

**Ответ**: JSON-массив из двух элементов `[meta, assetCtxs]`, где `meta.universe[i]` и
`assetCtxs[i]` — одна и та же монета по одному и тому же индексу `i`. Индекс BTC на 2026-09-25 —
`0`, но реализация должна искать индекс **по имени** (`meta.universe[i].name == "BTC"`), а не
хардкодить `0` — порядок `universe` не документирован как стабильный контракт.

**Нужные поля** (`assetCtxs[i]`, подтверждено живым вызовом §1.10):
- `oraclePx` — oracle price (используется для funding, см. §1.9).
- `markPx` — mark price (используется для margining/liquidations/uPnL, см. §1.5) — **это поле
  читает наш паблишер для публикации в Chia** (не `oraclePx`, т.к. движок MVP моделирует
  ликвидации по mark price, см. §2.2).
- `midPx` — опционально, для отображения/дебага.

Пример живого ответа (2026-09-25, ~14:10 UTC): `oraclePx=83492.0, markPx=83468.0, midPx=83474.5`
(полный фрагмент — §1.10).

---

## 5. Сценарии для тестов

Определения (предложение): «наибольшее дневное падение» = top-10 свечей `hl_btc_1d.csv` по
`(open - low) / open` убыв.; «наибольший дневной рост» = top-10 по `(high - open) / open` убыв.;
«самая экстремальная часовая свеча» = top-5 свечей `hl_btc_1h.csv` по
`max((open-low)/open, (high-open)/open)` убыв. (наибольшее однонаправленное движение внутри часа,
без разделения на рост/падение). Позиция — открыта по `open` свечи, `STANDARD_SIZE_BTC=0.10000`
(§2.7), long/short проверены на leverage 2..40 по правилам §2.3. Источник данных — фикстуры,
описанные в `engine/src/test/resources/fixtures/PROVENANCE.md`.

### 10 крупнейших дневных падений (low vs open), `hl_btc_1d.csv`

| UTC | open | high | low | close | long ликвидирован на leverage | short ликвидирован на leverage |
|---|---|---|---|---|---|---|
| 2021-05-19T00:00:00Z | 42882.5 | 43615.9 | 28688.0 | 36689.1 | 3-40 | 34-40 |
| 2021-12-04T00:00:00Z | 53611.9 | 53889.7 | 40888.9 | 49135.7 | 5-40 | none |
| 2021-01-11T00:00:00Z | 38200.0 | 38322.2 | 30448.0 | 35408.5 | 5-40 | none |
| 2021-09-07T00:00:00Z | 52684.0 | 52950.0 | 42088.0 | 46864.4 | 5-40 | none |
| 2022-11-08T00:00:00Z | 20578.5 | 20685.0 | 16830.0 | 18534.0 | 6-40 | none |
| 2022-06-13T00:00:00Z | 26564.7 | 26877.0 | 21900.0 | 22471.5 | 6-40 | none |
| 2021-05-21T00:00:00Z | 40500.0 | 42270.4 | 33400.0 | 37211.0 | 6-40 | 18-40 |
| 2025-10-10T00:00:00Z | 121615.0 | 122460.0 | 100837.0 | 112761.0 | 6-40 | none |
| 2021-02-23T00:00:00Z | 54125.6 | 54260.1 | 44893.8 | 48930.0 | 6-40 | none |
| 2021-05-23T00:00:00Z | 37436.0 | 38261.7 | 31096.7 | 34651.6 | 6-40 | 29-40 |

### 10 крупнейших дневных ростов (high vs open), `hl_btc_1d.csv`

| UTC | open | high | low | close | long ликвидирован на leverage | short ликвидирован на leverage |
|---|---|---|---|---|---|---|
| 2021-07-26T00:00:00Z | 35370.7 | 48168.6 | 35185.5 | 37218.1 | none | 3-40 |
| 2023-10-23T00:00:00Z | 29994.0 | 36504.0 | 29902.0 | 33121.0 | none | 5-40 |
| 2021-02-08T00:00:00Z | 38849.2 | 47146.0 | 38037.0 | 46451.3 | 31-40 | 5-40 |
| 2022-02-28T00:00:00Z | 37672.1 | 44341.0 | 37414.4 | 43155.0 | none | 6-40 |
| 2021-05-20T00:00:00Z | 36689.1 | 42460.0 | 34882.0 | 40500.3 | 17-40 | 6-40 |
| 2021-01-29T00:00:00Z | 33400.0 | 38641.1 | 31960.0 | 34269.4 | 19-40 | 6-40 |
| 2021-05-24T00:00:00Z | 34656.5 | 39917.1 | 34366.0 | 38779.7 | none | 7-40 |
| 2022-11-10T00:00:00Z | 15905.0 | 18230.0 | 15730.0 | 17588.0 | none | 7-40 |
| 2021-01-02T00:00:00Z | 29337.2 | 33480.0 | 28958.2 | 32199.9 | 40 | 7-40 |
| 2026-02-06T00:00:00Z | 62894.0 | 71684.0 | 60000.0 | 70516.0 | 18-40 | 7-40 |

Строки с непустыми обеими колонками (например 2021-05-19, 2021-05-21, 2021-05-23, 2021-02-08,
2021-05-20, 2021-01-29, 2021-01-02, 2026-02-06) — свечи, где внутри одного дня было движение в обе
стороны, ликвидирующее и часть long-, и часть short-леверreджей; для таких свечей правило §2.3
(«ликвидация приоритетнее закрытия раунда») применяется к обеим сторонам независимо — long и short
позиции на такой свече ликвидируются каждая по своему порогу, независимо друг от друга (это разные
трейдеры/разные позиции, не взаимоисключающие исходы).

### 5 самых экстремальных часовых свечей, `hl_btc_1h.csv`

Часовая история доступна только с `2026-03-01T04:00:00Z` (cap API, см.
`engine/src/test/resources/fixtures/PROVENANCE.md`) — поэтому все 5 строк из 2026 года.

| UTC | open | high | low | close | long ликвидирован на leverage | short ликвидирован на leverage |
|---|---|---|---|---|---|---|
| 2026-08-19T15:00:00Z | 65892.0 | 70224.0 | 65851.0 | 68532.0 | none | 13-40 |
| 2026-06-25T13:00:00Z | 61212.0 | 61336.0 | 58062.0 | 58234.0 | 16-40 | none |
| 2026-03-23T11:00:00Z | 68515.0 | 71502.0 | 68513.0 | 70075.0 | none | 18-40 |
| 2026-08-21T08:00:00Z | 76338.0 | 79584.0 | 76287.0 | 79317.0 | none | 19-40 |
| 2026-06-07T22:00:00Z | 61750.0 | 64228.0 | 61696.0 | 62833.0 | none | 19-40 |

---

## 6. Открытые вопросы

- **Trading fee (maker/taker) на открытие/закрытие позиции** — не задокументирован ни на одной из
  восьми зафетченных сегодня страниц (§1.1); в MVP принято `fees=0` (§2.5, предложение). Если
  понадобится реализм — отдельная страница доков Hyperliquid по fee schedule не входила в список
  задачи и не читалась в этом проходе.
- **Funding при многочасовых/многодневных раундах** — MVP-раунды закрываются в пределах одной
  свечи, поэтому funding не накапливается; если раунды станут длиннее одного интервала свечи,
  потребуется часовой временной ряд `oraclePx` (у нас его нет исторически — только OHLC), либо
  отдельный источник.
- **Ранняя история `hl_btc_1d.csv` (2020-08-19 и далее) с `volume=0, trades=0`** — похоже на
  backfill индексной цены до начала реальных торгов BTC-перпом на Hyperliquid; сама биржа так и
  отдаёт эти данные через `candleSnapshot`, отдельного подтверждения даты запуска реальных торгов
  BTC на Hyperliquid в этом проходе не искалось (не требовалось по заданию) — не блокирует
  использование фикстуры для тестов математики ликвидации (OHLC валиден независимо от объёма).
  Если для будущей презентации/питча понадобится точная дата запуска торгов — отдельная задача.
- **Разница между `candleSnapshot`-ценой (используется в реплее, §2.4) и настоящей исторической
  `markPx`** — не измерена численно (нет исторического ряда `markPx` через использованные
  read-only эндпоинты); в §2.4 зафиксировано как явное допущение MVP, не как численно
  подтверждённая аппроксимация.
- **Реализация maintenance_deduction/tier1** для BTC (notional > 150M USDC) — формула
  задокументирована (§1.4) и вычислена (`maintenance_deduction(tier1) = 1_875_000`), но не
  реализуется в MVP-движке (§2.1) — реалистичного сценария демо, достигающего этого порога, нет.
