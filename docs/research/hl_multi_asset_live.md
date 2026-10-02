# Hyperliquid: публичные данные по N активам в live-режиме

Дата: 2026-10-01. Слайс исследования для ветки `feature/markets-live-prices` (мульти-актив + реальные цены).
Вопрос: что Herd может читать у Hyperliquid сразу по N активам вместо одного BTC, как часто и с какими
параметрами на актив.

**Метод.** 44 живых read-only запроса `POST https://api.hyperliquid.xyz/info` (без ключей, последовательно,
паузы 1–2 с, 2026-10-01 19:32–19:44 UTC) и 21 GET страниц официальных доков `hyperliquid.gitbook.io`
(суффикс `.md`, fetched 2026-10-01). Запросы шли с одной машины через CloudFront (`X-Amz-Cf-Pop: TXL50-P2`,
`Via: ... (CloudFront)`, `X-Cache: Miss from cloudfront` во всех проверенных ответах). Все 44 ответа — валидный
JSON, ошибок и признаков ограничения не было; оценка по документированным весам — около 300–310 веса за самую
загруженную минуту при лимите 1200. Код проекта не менялся, WebSocket не открывался, Docker/стенд/ssh
не использовались. Сырые ответы лежат вне репозитория: `<scratchpad агента>/hyperliquid/`
(имена файлов — в §1).

**Правило фактов.** Каждый факт — со ссылкой на источник из §12 (`[Dn]` — страница доков, `[Ln]` — живой
запрос, таблица в §1) либо на `путь:строка` в репозитории, либо помечен **UNVERIFIED**. §10 — **предложение**
(механика данных), а не факт.

---

## 0. Сводка

1. **Один запрос даёт все активы.** `{"type":"metaAndAssetCtxs"}` возвращает `[meta, ctxs]`: 234 записи
   `universe` (178 живых + 56 с `isDelisted:true`) и 234 `ctx`, выровненных по индексу. Размер 71 900–72 191
   байт (14 замеров, среднее 72 025), вес 20 [D1], время ответа 0.31–1.01 с (13 замеров, медиана 0.56 с,
   среднее 0.64 с) [L2, L10]. `meta` внутри идентичен отдельному `{"type":"meta"}` (17 628 из 72 173 байт)
   [L1, L11]. Сервер не сжимает ответ (запрос с `Accept-Encoding: gzip` — 72 173 байта, без
   `Content-Encoding`) [L11].
2. **Таблица BTC/ETH/SOL из `multi_pair_architecture.md` §2 подтверждена без правок** (marginTableId 56/55/54,
   тиры, szDecimals 5/4/2). Индексы в `universe`: BTC 0, ETH 1, SOL 5 (§2).
3. **markPx/oraclePx меняются пачкой примерно раз в 2–3 с**, `midPx` в `ctx` — почти на каждом секундном опросе.
   `allMids` (mid, не mark) за ~10 с обновился дважды (§3).
4. **Бюджет `/info`: 1200 веса/мин на IP** [D1]. `metaAndAssetCtxs` раз в 2 с (как в `LivePriceSampler`) = 600/мин
   (50%); плюс `candleSnapshot` на раунд 3 мин × 10 активов = +66.7…70/мин; итого 666.7…670/мин (55.6–55.8%) (§5).
5. **`candleSnapshot`**: возвращает ещё не закрытую свечу; окно включает свечу, содержащую `startTime`; границы
   по `t` включительные с обеих сторон; закрытая минута BTC не менялась после B+0.26 с; у тихих активов хвост
   пустых минут отсутствует; потолок глубины ≈5000 (измерено 5058 строк в одном ответе) (§4).
6. **Не-крипто перпы есть и торгуются**: 10 builder-dex (HIP-3), 152 живых актива (`xyz` 110, `para` 29, `io` 8,
   `mkts` 5): акции, индексы, золото/нефть/серебро, FX (§7).
7. **Формулы maintenance margin и liquidation price по докам не зависят от актива**; от актива зависят
   `maxLeverage`/тиры, `szDecimals`, правила тика цены (§8).
8. **Расхождения кода с данными** (прочитано, не менялось): `LiveRoundPrices` в fallback берёт `response.get(0)` —
   свечу на минуту раньше нужной (проверено); `Candle` округляет OHLC до 2 знаков, у 13 из 178 живых активов
   `markPx < 0.005` → конструктор бросает исключение (§9).

---

## 1. Что именно запрашивалось

Базовый запрос: `POST https://api.hyperliquid.xyz/info`, `Content-Type: application/json`.

| ID | Тело запроса | Сколько раз | Когда (UTC) | Сырой файл |
|---|---|---|---|---|
| L1 | `{"type":"meta"}` | 1 | 19:32:29 | `meta.json` |
| L2 | `{"type":"metaAndAssetCtxs"}` с шагом 2 с | 5 | 19:33:57–19:34:09 | `mac_1..5.json`, `mac_timing.json` |
| L3 | `{"type":"allMids"}` | 2 | 19:36:07, 19:36:09 | `allmids_1..2.json` |
| L4 | `candleSnapshot` BTC 1m, `[now−180 с, now]` | 1 | 19:36:44 | `candle_btc_last3m.json` |
| L5 | `candleSnapshot` BTC 1m, `[B−120 с, B+60 с]`, B=19:38:00 (опросы с −3.0 до +5.2 с от B) | 7 | 19:37:57–19:38:05 | `candle_boundary_0..6.json`, `candle_boundary_log.json` |
| L6 | `candleSnapshot` BTC 1m, окно `[A−60 с, A+60 с]` для A=18:00:00 (выровнено) и A=18:00:17 | 2 | 19:38 (сразу после L5) | `candle_fallback_*.json`, `candle_fallback_semantics.json` |
| L7 | `candleSnapshot` BTC 1m: окно 10 000 мин до now; окно 7000…5100 мин назад | 2 | 19:38:47 | `candle_cap_*.json` |
| L8 | `candleSnapshot` 1m для `NOT` и `GRIFFAIN` (тихие активы), 60 мин + текущая | 2 | 19:39:23–26 | `candle_quiet_*.json` |
| L9 | `{"type":"allMids"}`, 7 опросов с шагом ~1.5 с | 7 | 19:39:50–19:39:59.6 | `allmids_cad_0..6.json`, `allmids_cadence_log.json` |
| L10 | `{"type":"metaAndAssetCtxs"}`, период 1 с | 8 | 19:40:30–19:40:37 | `mac_cad_0..7.json`, `mac_cadence_log.json` |
| L11 | `metaAndAssetCtxs` через `curl --compressed` (проверка сжатия) | 1 | 19:41:05 | `mac_gzip_test.json`, `mac_gzip_headers.txt` |
| L12 | `{"type":"perpDexs"}` | 1 | 19:41:16 | `perpDexs.json` |
| L13 | `{"type":"allPerpMetas"}` | 1 | 19:41:27 | `allPerpMetas.json` |
| L14 | `{"type":"metaAndAssetCtxs","dex":"xyz"}` | 1 | 19:41:52 | `mac_xyz.json` |
| L15 | `{"type":"perpCategories"}` | 1 | 19:42:07 | `perpCategories.json` |
| L16 | `candleSnapshot` BTC `3m`, `[now−12 мин, now]` | 1 | 19:43:08 | `candle_btc_3m.json` |
| L17 | `candleSnapshot` `xyz:TSLA` 1m, `[now−5 мин, now]` | 1 | 19:43:55 | `candle_xyz_TSLA_1m.json` |

Типы `allPerpMetas` и `perpCategories` документированы на той же странице, что `meta`/`perpDexs` [D3]; в слайсе
они использованы вместо 10 отдельных `meta` с `dex`, чтобы получить все не-крипто dex за один запрос.

Воспроизведение (пример):
```bash
curl -sS -X POST https://api.hyperliquid.xyz/info -H 'Content-Type: application/json' \
  -d '{"type":"candleSnapshot","req":{"coin":"BTC","interval":"1m","startTime":1790877540000,"endTime":1790877660000}}'
```

---

## 2. Параметры активов (`meta`, `marginTables`)

Снимок: `metaAndAssetCtxs` 2026-10-01 19:41:05 UTC [L11]. `l(tier0) = 1/(2·maxLeverage)` и `deduction(tier1)` —
**расчёт** по формулам из [D9] (`maintenance_margin_rate(tier n) = (Initial Margin Rate at Maximum leverage at
tier n) / 2`; `maintenance_deduction(tier n) = maintenance_deduction(tier n−1) + lowerBound(n)·(rate(n) −
rate(n−1))`), не поле API. «Знаков цены» = `6 − szDecimals` (правило тика ордера, [D7]).

### 2.1 Десять запрошенных активов

| Актив | idx | szDec | maxLev | marginTableId | Тиры (lowerBound USDC → maxLeverage) | l(tier0) | deduction(tier1), USDC | Знаков цены | markPx | oraclePx | midPx |
|---|---|---|---|---|---|---|---|---|---|---|---|
| BTC | 0 | 5 | 40 | 56 | 0→40x ; 150M→20x | 0.0125 | 1 875 000 | 1 | 84629.0 | 84668.9 | 84636.5 |
| ETH | 1 | 4 | 25 | 55 | 0→25x ; 100M→15x | 0.02 | 1 333 333.33 | 2 | 2696.8 | 2696.94 | 2697.25 |
| SOL | 5 | 2 | 20 | 54 | 0→20x ; 70M→10x | 0.025 | 1 750 000 | 4 | 117.84 | 117.8792 | 117.875 |
| XRP | 25 | 0 | 20 | 53 | 0→20x ; 40M→10x | 0.025 | 1 000 000 | 6 | 1.4964 | 1.496041 | 1.49675 |
| DOGE | 12 | 0 | 10 | 52 | 0→10x ; 20M→5x | 0.05 | 1 000 000 | 6 | 0.094534 | 0.094515 | 0.094572 |
| BNB | 7 | 3 | 10 | 51 | 0→10x ; 3M→5x | 0.05 | 150 000 | 3 | 768.38 | 768.75 | 768.58 |
| HYPE | 159 | 2 | 10 | 52 | 0→10x ; 20M→5x | 0.05 | 1 000 000 | 4 | 87.235 | 87.228 | 87.2665 |
| SUI | 14 | 1 | 10 | 52 | 0→10x ; 20M→5x | 0.05 | 1 000 000 | 5 | 1.1696 | 1.169 | 1.17 |
| AVAX | 6 | 2 | 10 | 52 | 0→10x ; 20M→5x | 0.05 | 1 000 000 | 4 | 10.975 | 10.971 | 10.9775 |
| LINK | 18 | 1 | 10 | 52 | 0→10x ; 20M→5x | 0.05 | 1 000 000 | 5 | 14.356 | 14.3585 | 14.3575 |

Ни у одного из десяти нет флагов `onlyIsolated`, `marginMode`, `isDelisted` [L11]. Тиры совпадают с таблицами
в [D9]: BTC, ETH, SOL, XRP — отдельные таблицы (56, 55, 54, 53); списки «AAVE, ADA, APT, AVAX, BCH, CRV, DOGE, ENA,
FARTCOIN, HYPE, kBONK, kPEPE, LINK, LTC, NEAR, PUMP, SUI, TRUMP, UNI, WLD, ZEC» («0-20M → 10, >20M → 5») и «ARB, BNB, DOT,
JUP, kSHIB, MKR, ONDO, PAXG, TON, TRX, XPL» («0-3M → 10, >3M → 5») из [D9] побуквенно совпадают с составом живых таблиц
52 (21 актив) и 51 (11 активов, из них MKR и TON — `isDelisted`) [L11].

### 2.2 Топ-15 по `dayNtlVlm` (живые, тот же снимок)

| # | Актив | idx | dayNtlVlm, USD | szDec | maxLev | marginTableId | Тиры | markPx | Есть в списке 10 |
|---|---|---|---|---|---|---|---|---|---|
| 1 | BTC | 0 | 2 178 650 439 | 5 | 40 | 56 | 0→40x ; 150M→20x | 84629.0 | да |
| 2 | ETH | 1 | 1 090 075 941 | 4 | 25 | 55 | 0→25x ; 100M→15x | 2696.8 | да |
| 3 | HYPE | 159 | 648 185 864 | 2 | 10 | 52 | 0→10x ; 20M→5x | 87.235 | да |
| 4 | ZEC | 214 | 434 667 870 | 2 | 10 | 52 | 0→10x ; 20M→5x | 1316.6893 | |
| 5 | NEAR | 74 | 345 389 804 | 1 | 10 | 52 | 0→10x ; 20M→5x | 4.7954 | |
| 6 | SOL | 5 | 204 980 675 | 2 | 20 | 54 | 0→20x ; 70M→10x | 117.84 | да |
| 7 | PUMP | 200 | 126 223 259 | 0 | 10 | 52 | 0→10x ; 20M→5x | 0.005746 | |
| 8 | XRP | 25 | 88 455 378 | 0 | 20 | 53 | 0→20x ; 40M→10x | 1.4964 | да |
| 9 | ENA | 122 | 59 273 592 | 0 | 10 | 52 | 0→10x ; 20M→5x | 0.24491 | |
| 10 | AAVE | 28 | 50 879 918 | 2 | 10 | 52 | 0→10x ; 20M→5x | 167.86 | |
| 11 | WLD | 31 | 42 866 690 | 1 | 10 | 52 | 0→10x ; 20M→5x | 0.49496 | |
| 12 | LIT | 223 | 42 472 245 | 0 | 5 | 5 (неявная) | 0→5x | 3.9018 | |
| 13 | SUI | 14 | 40 988 362 | 1 | 10 | 52 | 0→10x ; 20M→5x | 1.1696 | да |
| 14 | MON | 215 | 39 155 131 | 0 | 5 | 5 (неявная) | 0→5x | 0.035017 | |
| 15 | UNI | 39 | 29 342 917 | 1 | 10 | 52 | 0→10x ; 20M→5x | 9.0503 | |

Места остальных из десяти по объёму: LINK 17, AVAX 18, DOGE 22, BNB 47 [L11].

### 2.3 Секция `marginTables` и «неявные» таблицы

В ответе `meta` секция `marginTables` — 7 записей (id 50–56) [L1]:

| id | description | Тиры (lowerBound USDC → maxLeverage) | deduction(tier1) |
|---|---|---|---|
| 50 | `""` | 0→50x | — |
| 51 | tiered 10x | 0→10x ; 3M→5x | 150 000 |
| 52 | tiered 10x (2) | 0→10x ; 20M→5x | 1 000 000 |
| 53 | tiered 20x | 0→20x ; 40M→10x | 1 000 000 |
| 54 | tiered 20x (2) | 0→20x ; 70M→10x | 1 750 000 |
| 55 | tiered 25x | 0→25x ; 100M→15x | 1 333 333.33 |
| 56 | tiered 40x | 0→40x ; 150M→20x | 1 875 000 |

`marginTableId` у активов может быть и 3, 5, 10, 20, 25, 30 — таких id в `marginTables` нет. Док: «Margin tables
have unique IDs and the tiers can be found in the `meta` Info response. For IDs less than 50, there is a single
tier with max leverage equal to the ID.» [D9]. Живые активы основного dex используют неявные id 3 (88 активов)
и 5 (56 активов) [L11].

Проверка согласованности: в 531 записи всех 11 dex (`allPerpMetas`) `universe[].maxLeverage` равен `maxLeverage`
тира 0 своей таблицы (явной или неявной), `marginTableId` присутствует во всех записях [L13]. Примеры
в доках (`BTC maxLeverage 50`, записи без `marginTableId`) иллюстративны и не совпадают с живыми данными
(BTC: 40, 56) [D3, L1].

### 2.4 Структура `universe` основного dex

- Поля записи: `name`, `szDecimals`, `maxLeverage`, `marginTableId`; опционально `onlyIsolated`, `isDelisted`,
  `marginMode` (`strictIsolated`) [L1]. Док: «"strictIsolated" means margin cannot be removed, "noCross" means only
  isolated margin allowed»; `onlyIsolated` — deprecated [D3].
- 234 записи = 178 живых + 56 `isDelisted:true`; delisted остаются в `universe` и в массиве `ctx` на своих
  индексах [L1, L11]. У всех 56 delisted: `midPx: null`, `premium: null`, `impactPxs: null`, `dayNtlVlm: "0.0"`,
  а `markPx` заморожен (пример MATIC: `markPx = prevDayPx = "0.37621"`) [L11].
- `onlyIsolated: true` только у 8 записей, все delisted; среди 178 живых нет ни одного `onlyIsolated`/`marginMode`
  [L1, L11].
- Живые, `maxLeverage`: 40x — 1 (BTC); 25x — 1 (ETH); 20x — 2 (SOL, XRP); 10x — 30; 5x — 56; 3x — 88. По
  `marginTableId`: 3 — 88, 5 — 56, 51 — 9, 52 — 21, 53 — 1, 54 — 1, 55 — 1, 56 — 1. По `szDecimals`: 0 — 98,
  1 — 51, 2 — 22, 3 — 5, 4 — 1, 5 — 1 [L11].
- Порядок `universe` совпал между снимками 19:34 и 19:41; новые активы стоят в хвосте (последние: …`CASHCAT`,
  `PONS` (232), `USELESS` (233)), максимальный индекс delisted-записи — 202 [L2, L11]. Гарантии стабильности
  индекса в доках нет — **UNVERIFIED** за пределами этих наблюдений (BTC=0, ETH=1 совпадает с
  `docs/research/hl_liquidation_math.md` §1.10 от 2026-09-25 и `multi_pair_architecture.md` §2 от 2026-09-26).
- Активов с `dayNtlVlm` ≥ $100 тыс. — 160, ≥ $1 млн — 82, ≥ $10 млн — 27, ≥ $100 млн — 7; медиана по живым —
  $793 396/сутки [L11].

### 2.5 Сверка с `multi_pair_architecture.md` §2

| Актив | В §2 документа (2026-09-26) | Живой `meta` 2026-10-01 | Итог |
|---|---|---|---|
| BTC | 56; `[0,150M)→40x`, `[150M,∞)→20x`; szDecimals 5 | 56; 0→40x, 150M→20x; 5 | совпадает |
| ETH | 55; `[0,100M)→25x`, `[100M,∞)→15x`; 4 | 55; 0→25x, 100M→15x; 4 | совпадает |
| SOL | 54; `[0,70M)→20x`, `[70M,∞)→10x`; 2 | 54; 0→20x, 70M→10x; 2 | совпадает |

Уточнение к §5.3 того документа («у Hyperliquid их 234»): 234 — размер `universe` вместе с 56 delisted; живых 178.

---

## 3. Живые цены: `metaAndAssetCtxs`, `allMids`

### 3.1 `metaAndAssetCtxs`

- Запрос: `{"type":"metaAndAssetCtxs"}` [D3]; параметр `dex` (по умолчанию пустая строка — первый dex) [D3].
- Ответ: массив из двух элементов `[meta, ctxs]`; `meta = {universe, marginTables, collateralToken}`; `ctxs[i]`
  соответствует `universe[i]` [L2, L11].
- Поля `ctx` (все значения — строки): `markPx`, `oraclePx`, `midPx`, `funding`, `openInterest`, `prevDayPx`,
  `dayNtlVlm`, `dayBaseVlm`, `premium`, `impactPxs` (массив из двух строк) [L2]. Пример BTC (19:34:08):
  ```json
  {"funding":"0.0000125","openInterest":"34444.10858","prevDayPx":"83739.0","dayNtlVlm":"2201748442.1872768402",
   "premium":"-0.0004719096","oraclePx":"84762.0","markPx":"84721.0","midPx":"84721.5",
   "impactPxs":["84721.0","84722.0"],"dayBaseVlm":"26215.96241"}
  ```
- Размер: 72 025 байт в среднем (14 замеров, 71 900–72 191); из них `meta` 17 628 байт, массив `ctxs`
  54 542 байт (≈233 байта на актив) [L2, L10, L11].
- Время ответа (клиент без keep-alive, `urllib`): серия из 5 вызовов с шагом 2 с — 0.822, 0.309, 0.540, 1.008,
  0.933 с (среднее 0.722); серия из 8 вызовов — 0.317–0.835 с (среднее 0.586); все 13: медиана 0.563 с [L2, L10].
  `curl` с keep-alive: подключение 0.021 с, TLS 0.039 с, первый байт 0.385 с, всего 0.804 с [L11]. Замеры с одной
  точки, **UNVERIFIED** для места, где будет жить Herd.
- Одного вызова хватает на все активы основного dex. Для каждого HIP-3 dex нужен отдельный вызов с `dex`
  (`xyz`: 129 записей, 57 680 байт) [L14, D3].

### 3.2 Частота обновления полей `ctx` (8 опросов с периодом 1 с, 19:40:30–19:40:37)

Число из 178 живых активов, у которых поле изменилось относительно предыдущего опроса [L10]:

| Опрос | Отправлен | BTC markPx / oraclePx / midPx | markPx изменился | oraclePx изменился | midPx изменился |
|---|---|---|---|---|---|
| 0 | 30.158 | 84610.0 / 84651.4 / 84611.5 | — | — | — |
| 1 | 31.153 | то же | 0 | 0 | 103 |
| 2 | 32.156 | то же | 0 | 0 | 95 |
| 3 | 33.152 | то же | 0 | 0 | 0 |
| 4 | 34.157 | 84611.0 / 84651.6 / 84611.5 | 105 | 106 | 93 |
| 5 | 35.157 | то же | 0 | 0 | 78 |
| 6 | 36.147 | 84611.0 / 84652.8 / 84611.5 | 82 | 79 | 70 |
| 7 | 37.156 | то же | 0 | 0 | 106 |

- `markPx` и `oraclePx` обновляются одновременно и пачкой (у ~80–105 активов сразу, у остальных значение не
  менялось); за 8 с — два обновления, между опросами 3→4 и 5→6, интервал порядка 1–3 с. Док: «Mark price is
  updated whenever validators publish new oracle prices. Therefore, mark and oracle price are updated
  approximately once every 3 seconds.» [D14]. В серии из 5 вызовов с шагом 2 с (L2, вызовы 3 и 4, разница между
  отправками 2.55 с) `markPx` и `oraclePx` BTC не изменились (84712.0 / 84750.0), `midPx` изменился
  (84718.5 → 84719.5) [L2].
- `midPx` в `ctx` меняется почти на каждом секундном опросе (у 70–106 активов), кроме опроса 3 [L10].
- Выборка мала (2 события за 8 с + 5 замеров с шагом 2 с): точное распределение интервалов между обновлениями
  **UNVERIFIED**.

### 3.3 `allMids`

- Запрос: `{"type":"allMids"}`, необязательный `dex` [D2]. Док: «if the book is empty, the last trade price will
  be used as a fallback»; «Spot mids are only included with the first perp dex» [D2]. Вес 2 [D1].
- Ответ: плоский объект `{ключ: "строка-цена"}` [L3]. Размер 19 883–19 896 байт; время 0.29–0.57 с; 1131 ключ [L3, L9]:
  234 имени перпов (весь `universe`, включая 56 delisted), 376 ключей `@N` (спот), 1 `PURR/USDC`, 520 ключей `#N`
  [L3]. Ключей с `:` (HIP-3) в ответе без `dex` нет [L3].
- Ключи `#N` — стороны outcome-рынков (HIP-4): по [D8] «Outcome spot coin: `#<encoding>`», `encoding = 10 * outcome +
  side`. В снимке 19:36:07 — 260 outcome-id (1472…7408), у каждого обе стороны, значения в диапазоне
  0.00034…0.99966, сумма двух сторон ровно 1.0 [L3]. Что это за рынки — не исследовано (`outcomeMeta` не
  запрашивался, вне слайса); в [D12] описаны «Recurring outcomes» (`class:priceBinary`/`priceBucket`, underlying BTC,
  period 1d/15m — примеры из доки); в [D5] есть подписка `outcomeMetaUpdates` — **UNVERIFIED вживую**.
- Частота обновления (7 опросов, 19:39:50–19:39:59.6): значения менялись только между опросами 1→2 и 4→5 (151 и 155
  ключей из 1131; перпов — по 112); BTC: 84617.5 (опросы 0–4) → 84615.5 (опросы 5–6); ETH: 2696.35 (0–1) →
  2696.55 (2–6) [L9]. В первом тесте два вызова с интервалом 2 с вернули одинаковое содержимое (все 1131 значение) [L3]. Интервал
  между двумя наблюдёнными обновлениями — 3.0–6.3 с; то есть `allMids` обновляется заметно реже, чем `midPx`
  в `ctx` (§3.2). Причина (кэш или другой источник) — **UNVERIFIED**.
- `allMids` не содержит `markPx`, `oraclePx`, `prevDayPx`, `dayNtlVlm`, `funding` [L3].

### 3.4 mark, mid и oracle: расхождения

Снимок 19:41:05 [L11], разность в базисных пунктах относительно `markPx`:

| Актив | (mid − mark), bp | (oracle − mark), bp |
|---|---|---|
| BTC | +0.89 | +4.71 |
| ETH | +1.67 | +0.52 |
| SOL | +2.97 | +3.33 |
| XRP | +2.34 | −2.40 |
| DOGE | +4.02 | −2.01 |
| BNB | +2.60 | +4.82 |
| HYPE | +3.61 | −0.80 |
| SUI | +3.42 | −5.13 |
| AVAX | +2.28 | −3.64 |
| LINK | +1.04 | +1.74 |

По всем 178 живым активам, по полосам суточного объёма (медиана / максимум модуля, bp): ≥$100M — mid−mark 3.61 / 24.36;
$10–100M — 5.12 / 8.92; $1–10M — 5.26 / 14.59; $100k–1M — 5.53 / 21.09; <$100k — 6.20 / 20.58; oracle−mark: 1.17 / 6.96,
2.44 / 10.59, 4.82 / 23.36, 6.72 / 32.74, 7.10 / 45.96 [L11]. Один снимок: статистику по времени **не** измеряли.
Какая цена используется для ликвидаций: «Liquidations use the mark price» [D10]; mark — медиана трёх входов
(oracle + EMA разницы mid−oracle; медиана best bid/best ask/last trade; медиана mid-цен Binance/OKX/Bybit/Gate/MEXC
с весами 3/2/2/1/1) [D14].

### 3.5 Точность цен в ответах

- В 14 снимках `metaAndAssetCtxs` (7 476 значений `markPx`/`oraclePx`/`midPx` живых активов) максимум знаков после
  запятой — 6, и ни одно значение не нарушает «знаков ≤ 6 − szDecimals» [L2, L10, L11].
- Значащих цифр у `markPx` бывает больше 5: в снимке 19:41:05 — `ZEC 1316.6893` (8 цифр) и `GRAM 1.542661` (7); в снимке 19:34:08 —
  13 активов (например `AVAX 10.9812`) [L2, L11]. Правило «до 5 значащих цифр» из [D7] относится к ценам ордеров, не к `markPx`.
- Из 178 живых активов `markPx` < $1 у 132, < $0.1 у 76, < $0.01 у 25, < $0.005 у 13 (ANIME, BOME, GALA, HMSTR, LINEA,
  MEME, NOT, REZ, RSR, SOPH, TURBO, kBONK, kPEPE) [L11].
- Правило тика (док): «Prices can have up to 5 significant figures, but no more than `MAX_DECIMALS - szDecimals`
  decimal places where `MAX_DECIMALS` is 6 for perps»; «Sizes are rounded to the `szDecimals` of that asset»
  [D7].

---

## 4. `candleSnapshot` (1m и 3m)

Форма: `{"type":"candleSnapshot","req":{"coin":"BTC","interval":"1m","startTime":<ms>,"endTime":<ms>}}`; интервалы
«1m, 3m, 5m, 15m, 30m, 1h, 2h, 4h, 8h, 12h, 1d, 3d, 1w, 1M»; для HIP-3 имя с префиксом dex (`xyz:XYZ100`) [D2].
Ответ — массив по возрастанию `t`; строка: `t`, `T` (числа, мс), `s` (coin), `i` (interval), `o`,`h`,`l`,`c`,`v`
(строки), `n` (число сделок) [L4]. Веб-сокетный тип `Candle` в доке описан с числовыми `o,c,h,l,v` [D5] —
формат на проводе **UNVERIFIED**.

### 4.1 Измерения

| Вопрос | Результат | Источник |
|---|---|---|
| Возвращается ли ещё открытая свеча | Да. В 19:36:44.4 запрос вернул свечу `t=19:36:00`, `T=19:36:59.999` (в будущем), `n=140` | L4 |
| `T` у открытой свечи | Номинальное время закрытия: `T = t + 59 999` (1m), `T = t + 179 999` (3m) | L4, L16 |
| Окно не по границе минуты | `startTime=19:33:44.410` → первая строка `t=19:33:00` (свеча, содержащая `startTime`); `endTime=19:36:44.410` → последняя `t=19:36:00` (свеча, содержащая `endTime`) | L4 |
| Окно по границам минуты | `[A−60 с, A+60 с]`, A=18:00:00 → 3 строки: `t=A−60 с`, `A`, `A+60 с` (обе границы по `t` включительные) | L6 |
| То же, A=18:00:17 | `[A−60 с, A+60 с]` → те же 3 строки: `t=17:59:00`, `18:00:00`, `18:01:00` | L6 |
| Когда появляется свеча новой минуты | Со своей первой сделкой. Для BTC минуты B=19:38:00: в запросе, отправленном за 0.085 с до B (ответ в B+0.26 с), свечи `t=B` нет; в запросе, отправленном в B+1.27 с, она есть (`n=5`). У тихого `GRIFFAIN` текущей минуты в ответе нет через 26 с после её начала (§4.3) | L5, L8 |
| Меняется ли закрытая минута после закрытия | Свеча `t=19:37:00`: `c=84586.0, v=4.18768, n=158` в опросе, отправленном в B−0.085 с (ответ B+0.26 с), и без изменений в опросах B+1.27, +2.57, +3.88, +5.19 с. До этого: n=155 (B−3.0 с), n=157 (B−1.4 с) | L5 |
| Глубина (1m) | Окно 10 000 мин до now → **5058 строк** в одном ответе (683 297 байт, 1.49 с), первая `t=2026-09-28 07:21:00`, пропусков нет. Окно 7000…5100 мин назад → пустой массив (`[]`, 2 байта) | L7 |
| Сетка 3m | `t % 180000 == 0` у всех 5 строк (BTC), последняя строка — открытая | L16 |
| HIP-3 | `coin="xyz:TSLA"` возвращает ту же структуру, `s="xyz:TSLA"` | L17 |

Подробности опросов вокруг границы минуты (B = 19:38:00.000, время отправки/получения относительно B; свеча
`t=19:37:00` и свеча `t=B`) [L5]:

| Опрос | Отправлен | Получен | Свеча 19:37 (c / v / n) | Свеча 19:38 (c / v / n) |
|---|---|---|---|---|
| 0 | −2.997 с | −2.417 с | 84586.0 / 4.15938 / 155 | нет |
| 1 | −1.413 с | −1.090 с | 84585.0 / 4.18754 / 157 | нет |
| 2 | −0.085 с | +0.260 с | 84586.0 / 4.18768 / 158 | нет |
| 3 | +1.269 с | +1.569 с | 84586.0 / 4.18768 / 158 | 84585.0 / 0.01415 / 5 |
| 4 | +2.571 с | +2.872 с | то же | 84586.0 / 2.03309 / 16 |
| 5 | +3.883 с | +4.180 с | то же | 84586.0 / 2.03897 / 18 |
| 6 | +5.191 с | +5.504 с | то же | 84581.0 / 2.15255 / 31 |

Итог по таймингу: свеча минуты M присутствует в ответе уже во время самой минуты M (с `T` в будущем, значения растут), на границе
она становится закрытой без смены строки; отдельного «момента появления» после закрытия нет. Для BTC финальные значения закрытой
минуты были в ответе к B+0.26 с и далее не менялись [L4, L5]. В запросе L4 (19:36:44.4) предыдущая закрытая свеча (`T=19:35:59.999`)
отстояла от `now` на 44.4 с, текущая (`T=19:36:59.999`) опережала `now` на 15.6 с.

Заголовок `Date` ответов совпадал с локальными часами по секундам (расхождение < 1 с, разрешение заголовка 1 с)
[L5]. Время ответа `candleSnapshot` 0.29–0.58 с на маленьких окнах [L4–L7, L16, L17].

Поля `isClosed` в строке нет; закрытость определяется по `T < now` [L4].

### 4.2 Окно `fetchFallbackCandle` и выбор строки

`LiveRoundPrices.java:68-72` шлёт `startTime = atMs − 60 000`, `endTime = atMs + 60 000`, `LiveRoundPrices.java:77` берёт
`response.get(0)`. Фактический ответ на такое окно [L6]:

| atMs | Строки ответа (t относительно atMs) | Индекс свечи с `t == atMs` / содержащей `atMs` |
|---|---|---|
| 18:00:00.000 (выровнено) | −60 с, 0, +60 с | 1 |
| 18:00:17.000 | −77 с, −17 с, +43 с | 1 |

То есть `get(0)` — свеча предыдущей минуты. Для A=18:00:00: `get(0).o = 84780.0` против `get(1).o = 84871.0`
(свеча `t=A`), разница 91 USD (≈0.11%) [L6].

### 4.3 Тихие активы

`NOT` (суточный объём $38 608 в снимке 19:41:05) и `GRIFFAIN` ($38 565) в окне «60 мин + текущая» (61 ожидаемая минута) [L8, L11]:

| Актив | Строк | Свечи с `n=0` (`v=0`) | Минут без строки | Что пропущено |
|---|---|---|---|---|
| NOT | 46 из 61 | 40 | 15 | хвост: `19:25…19:39`, последняя строка `t=19:24` с `n=4` |
| GRIFFAIN | 60 из 61 | 41 | 1 | текущая минута `19:39`, последняя строка `t=19:38` с `n=3` |

- Внутри ряда минуты без сделок есть как свечи-заполнители: `n=0`, `v="0.0"`, `o=h=l=c` = `c` предыдущей строки
  (39 из 39 и 40 из 40 заполнителей равны предыдущему закрытию) [L8].
- Заполнителей **после** последней строки с `n>0` нет: хвост из минут без сделок отсутствует (0 замыкающих
  заполнителей в обоих рядах) [L8]. Наблюдение: заполнители появляются «задним числом», когда на следующей минуте
  случается сделка; механизм в доках не описан — **UNVERIFIED**.
- Следствие: у активов с малым оборотом отсутствие свечи последней минуты — нормальное состояние, а не ошибка.

### 4.4 Что не удалось определить по глубине

Документ: «Only the most recent 5000 candles are available» [D2]. Измерено 5058 строк в одном ответе; почему не
ровно 5000 и считается ли потолок по числу свечей или по времени — **UNVERIFIED**. Общая оговорка из [D2] «Responses
that take a time range will only return 500 elements or distinct blocks of data» к `candleSnapshot` на практике не
применилась (5058 строк в одном ответе).

---

## 5. Лимиты `/info` и арифметика бюджета

### 5.1 Документированные лимиты ([D1], fetched 2026-10-01)

> «The following rate limits apply per IP address: REST requests share an aggregated weight limit of 1200 per minute.»
>
> «The following `info` requests have weight 2: `l2Book, allMids, clearinghouseState, orderStatus, spotClearinghouseState, exchangeStatus.`»
>
> «The following `info` requests have weight 60: `userRole`.»
>
> «All other documented `info` requests have weight 20.»
>
> «The `candleSnapshot` info endpoint has an additional rate limit weight per 60 items returned in the response.»
>
> «Maximum of 10 websocket connections»; «Maximum of 30 new websocket connections per minute»; «Maximum of 1000 websocket
> subscriptions»; «Maximum of 2000 messages sent to Hyperliquid per minute across all websocket connections».
>
> «Use websockets for lowest latency realtime data.»

Из этого: `meta`, `metaAndAssetCtxs`, `perpDexs`, `allPerpMetas`, `perpCategories` — вес 20; `allMids` — 2;
`candleSnapshot` — 20 + надбавка за каждые 60 строк ответа. Точная формула надбавки (округление вниз/вверх),
вид окна лимита (скользящее/фиксированное), код и тело ответа при превышении — в доках нет [D1, D16] —
**UNVERIFIED**; превышение лимита не проверялось.

### 5.2 Бюджет (1200 веса/мин на IP)

| Сценарий | Вызовов/мин | Вес вызова | Веса/мин | Доля лимита |
|---|---|---|---|---|
| A. `metaAndAssetCtxs` каждые 2 с (как `LivePriceSampler.java:42`) | 30 | 20 | 600 | 50.0% |
| A′. то же каждые 3 с | 20 | 20 | 400 | 33.3% |
| A″. то же каждую 1 с | 60 | 20 | 1200 | 100% |
| B. `allMids` каждые 2 с | 30 | 2 | 60 | 5.0% |
| C. `candleSnapshot` на каждый завершённый раунд 3 мин, 10 активов (10 вызовов / 3 мин) | 3.33 | 20 (21, если надбавка округляется вверх до 1 на ≤60 строк) | 66.7…70 | 5.6…5.8% |
| A + C | | | 666.7…670 | 55.6…55.8% (запас 530…533) |
| B + C | | | 126.7…130 | 10.6…10.8% |
| A + C, все 10 запросов C в одну секунду на границе раунда | | | 800…810 в этой минуте | 66.7…67.5% |
| Два live-стенда за одним публичным IP, у обоих сценарий A | 60 | 20 | 1200 | 100% |

- Нагрузка C растёт линейно по числу активов: `20·N/3` веса/мин (N=10 → 66.7; N=30 → 200). При сценарии A остаётся
  600 веса/мин, то есть при C как единственном остальном потребителе предел — N = 90 активов.
- В C учтён худший случай «свеча — основной источник закрытия раунда». Когда закрытие берётся из буфера тиков, а
  `candleSnapshot` нужен только как fallback (`LiveRoundPrices.java:47-51`), вызовов C в установившемся режиме ≈ 0.
- Трафик: A — 43 200 вызовов/сут × 72 025 байт ≈ 3.11 ГБ/сут (сжатия нет, §0 п.1); B — 43 200 × ~19 890 байт ≈ 0.86 ГБ/сут.
- «Per IP address» [D1]: всё, что идёт с одного публичного IP (несколько стендов, другие сессии, утилиты проекта),
  делит один бюджет.

---

## 6. WebSocket: документированная форма (без подключения)

Источники: [D4], [D5], [D6], лимиты — [D1]. Живая проверка не проводилась — всё ниже **UNVERIFIED вживую**.

- URL: `wss://api.hyperliquid.xyz/ws` [D4].
- Подписка: `{"method":"subscribe","subscription":{...}}`; отписка — `{"method":"unsubscribe","subscription":{...}}`; ответ
  `{"channel":"subscriptionResponse","data":{...}}`; данные приходят с `channel` = тип подписки и полем `data` [D5].
  Для временных рядов первый ответ — снимок с `isSnapshot: true` [D5].
- `allMids`: `{"type":"allMids","dex":"<dex>"}` (`dex` необязателен; спот только с первым dex); `data = AllMids { mids:
  Record<string,string> }` [D5].
- `activeAssetCtx`: `{"type":"activeAssetCtx","coin":"<coin_symbol>"}`; `data = WsActiveAssetCtx { coin, ctx }`, где для
  перпов `ctx = { dayNtlVlm, prevDayPx, markPx, midPx?, funding, openInterest, oraclePx }` (в TypeScript-описании доки типы
  `number`, в REST — строки) [D5].
- `candle`: `{"type":"candle","coin":"<coin_symbol>","interval":"<candle_interval>"}`; `data = Candle[]` (`t, T, s, i, o, c, h, l, v,
  n`) [D5]. Признака закрытия свечи в описании типа нет.
- Дополнительно в той же доке: `allDexsAssetCtxs` (`{"type":"allDexsAssetCtxs"}` → `ctxs: Array<[string,
  Array<PerpsAssetCtx>]>`), `fastAssetCtxs` (`{"type":"fastAssetCtxs"}` → base64 от «raw DEFLATE stream (RFC 1951)», внутри
  JSON `{"BTC":{"markPx":"..."},...}`, поля `markPx`, `midPx`; «The first message is a snapshot, subsequent messages
  contain only coins that have updated»), `bbo`, `trades`, `l2Book`, `outcomeMetaUpdates` [D5].
- Жизнь соединения: «The server will close any connection if it hasn't sent a message to it in the last 60 seconds»;
  держать `{"method":"ping"}` → `{"channel":"pong"}` [D6]. «all automated users should handle disconnects from the server
  side and gracefully reconnect... Missed data during the reconnect will be present in the snapshot ack on reconnect»
  [D4].
- Лимиты WS: ≤10 соединений, ≤30 новых соединений/мин, ≤1000 подписок, ≤2000 сообщений/мин, отправляемых клиентом [D1].

---

## 7. Не-крипто перпы (HIP-3, builder-deployed dex)

### 7.1 Что вернули `perpDexs` и `allPerpMetas` (2026-10-01 19:41) [L12, L13]

`perpDexs` — массив из 11 элементов: первый `null` (основной dex), затем 10 builder-dex. `allPerpMetas` — массив из 11
объектов `{universe, marginTables, collateralToken}` в том же порядке (в примере доки — пары `[meta, ctxs]`: расхождение
с живым ответом) [D3, L13].

| dex | fullName | Записей в universe | Живых | collateralToken |
|---|---|---|---|---|
| (основной) | — | 234 | 178 | 0 |
| `xyz` | XYZ | 129 | 110 | 0 |
| `flx` | Felix Exchange | 16 | 0 | 360 |
| `vntl` | Ventuals | 15 | 0 | 360 |
| `hyna` | HyENA | 25 | 0 | 235 |
| `km` | Markets by Kinetiq | 23 | 0 | 360 |
| `abcd` | ABCDEx | 1 | 0 | 0 |
| `cash` | dreamcash | 17 | 0 | 268 |
| `para` | Paragon | 36 | 29 | 0 |
| `mkts` | Markets By Kinetiq | 24 | 5 | 0 |
| `io` | EntropyIO | 11 | 8 | 0 |

Всего 297 записей не-основных dex, 152 живых. Имена активов — всегда `{dex}:{coin}` [D8, L13].

### 7.2 Категории (`perpCategories`, 208 пар `[coin, category]`) [L15]

Живые активы по категориям: stocks — 119 (+1 с категорией `stock`: `para:AAOI`), commodities — 9, indices — 7, crypto — 5,
fx — 3, preipo — 3, rates — 1; без категории — 4 (`para:30Y`, `para:2Y`, `mkts:BVIV`, `io:EWY`). Для основного dex категорий нет
(в списке нет ни одного имени без `:`).

Символы живых активов как вернул API:
- **commodities**: `xyz:GOLD`, `xyz:SILVER`, `xyz:CL`, `xyz:COPPER`, `xyz:NATGAS`, `xyz:PLATINUM`, `xyz:PALLADIUM`, `xyz:BRENTOIL`, `xyz:HO`.
- **indices**: `xyz:XYZ100`, `xyz:KR200`, `xyz:JP225`, `xyz:SP500`, `mkts:US500`, `mkts:SMALL2000`, `mkts:USTECH`.
- **fx**: `xyz:JPY`, `xyz:EUR`, `xyz:GBP`. **rates**: `para:10Y` (и без категории `para:30Y`, `para:2Y`). **preipo**: `xyz:OURA`, `io:OAI`, `io:ANTH`.
- **crypto-индексы**: `para:TOTAL2`, `para:OTHERS`, `para:BTCD`, `para:ANSEM`, `para:TREAD`.
- **stocks (примеры из 119)**: `xyz:TSLA`, `xyz:NVDA`, `xyz:AAPL`, `xyz:MSFT`, `xyz:GOOGL`, `xyz:AMZN`, `xyz:META`, `xyz:NFLX`, `xyz:AMD`,
  `xyz:COIN`, `xyz:MSTR`, `xyz:BABA`, `xyz:TSM`, `para:AVGO`, `io:IONQ`, `mkts:USBOND`; полный список — `allPerpMetas.json` и
  `perpCategories.json`.
- Категорий «sports»/«politics» в `perpCategories` нет (имеющиеся: stocks, indices, commodities, crypto, preipo, fx, FX, rates, stock) [L15].

### 7.3 Живые данные по `xyz` (19:41:52) [L14]

`metaAndAssetCtxs` с `dex:"xyz"` — структура та же (`[meta, ctxs]`), 129 записей; у всех 110 живых записей поля `markPx`, `oraclePx`,
`midPx`, `premium`, `impactPxs`, `funding`, `prevDayPx`, `dayNtlVlm` не `null`. `meta` совпадает с элементом 1 из `allPerpMetas`.

| Символ | idx | szDec | maxLev | marginTableId | Режим | markPx | dayNtlVlm, USD |
|---|---|---|---|---|---|---|---|
| xyz:XYZ100 | 0 | 4 | 30 | 30 (неявная) | — | 30557.0 | 292 787 026 |
| xyz:SP500 | 52 | 3 | 50 | 50 | — | 7676.3 | 356 764 403 |
| xyz:GOLD | 3 | 4 | 25 | 25 (неявная) | — | 4176.3 | 40 055 004 |
| xyz:SILVER | 26 | 2 | 25 | 25 (неявная) | — | 60.966 | 187 086 721 |
| xyz:BRENTOIL | 49 | 2 | 20 | 20 (неявная) | — | 102.37 | 192 954 584 |
| xyz:TSLA | 1 | 3 | 20 | 20 (неявная) | — | 357.74 | 23 698 142 |
| xyz:NVDA | 2 | 3 | 20 | 20 (неявная) | — | 231.7 | 130 677 904 |
| xyz:AAPL | 9 | 3 | 20 | 20 (неявная) | — | 329.81 | 20 424 601 |
| xyz:EUR | 25 | 1 | 50 | 50 | `noCross`, `onlyIsolated` | 1.1247 | 6 484 727 |
| xyz:JPY | 24 | 2 | 50 | 50 | `noCross`, `onlyIsolated` | 158.01 | 3 586 910 |
| xyz:GBP | 75 | 0 | 50 | 50 | `noCross`, `onlyIsolated` | 1.3198 | 474 545 |

Живые активы `xyz`: `maxLeverage` 50×4, 30×1, 25×2, 20×24, 10×78, 5×1; `marginMode`: `noCross` — 90, не задан — 20; `szDecimals`:
0×3, 1×12, 2×51, 3×36, 4×7, 5×1; у записей есть дополнительные поля `growthMode`, `deployerFeeScale`, `lastFeeScaleChangeTime`
[L13, L14]. Таблица `marginTables` у `xyz` — только id 50 (0→50x). Свечи `xyz:TSLA` 1m получены в 19:43:55 (6 строк, последняя открыта) [L17].

### 7.4 Что доки говорят о HIP-3

- Оператор рынка (deployer) отвечает за «setting oracle prices, leverage limits, and settling the market» [D15]; «The
  deployer may settle an asset using the `haltTrading` action. This cancels all orders and settles positions to the current
  mark price» [D15].
- Для HIP-3 `dex` имеет собственные маржинальные режимы (`noCross`, `strictIsolated`) и своё залоговое средство (`collateralToken`
  отличается от 0 у `flx`, `vntl`, `hyna`, `km`, `cash`) [D11, L13].
- Поведение `markPx` и свечей HIP-3-активов вне торговых часов базового рынка (акции, индексы, FX — выходные, ночь) —
  **UNVERIFIED**: замеры сделаны в четверг 19:41–19:44 UTC.

---

## 8. Формулы ликвидации и maintenance margin: привязка к активу

Цитаты (fetched 2026-10-01):

- «On Hyperliquid, `maintenance_margin_rate` and `maintenance_deduction` depend only on the margin tiers, not the asset.» [D9]
- «`maintenance_margin_rate(tier = n) = (Initial Margin Rate at Maximum leverage at tier n) / 2`. For example, at 20x max
  leverage, `maintenance_margin_rate = 2.5%`.» [D9]
- «The maintenance margin is half of the initial margin at max leverage, which varies from 3-40x. In other words, the
  maintenance margin is between 1.25% (for 40x max leverage assets) and 16.7% (for 3x max leverage assets) depending on the
  asset.» [D10]
- «`liq_price = price - side * margin_available / position_size / (1 - l * side)` where `l = 1 / MAINTENANCE_LEVERAGE`. For assets
  with margin tiers, maintenance leverage depends on the unique margin tier corresponding to the position value at the
  liquidation price.» [D10]
- «`margin_available (isolated) = isolated_margin - maintenance_margin_required`» [D10]
- «Leverage can be set by a user to any integer between 1 and the max leverage. Max leverage depends on the asset.» [D11]

Вывод по докам: формула `liq_price` и правило `maintenance_margin` из `hl_liquidation_math.md` §1.4/§1.6/§2.2 не содержат
ничего специфичного для BTC; в них подставляются `l` тира актива и `isolated_margin`. Расхождение с докой в этом файле нет.

Что различается по активу (из ответов API и доков):

| Параметр | Откуда | Значения (живые, основной dex) |
|---|---|---|
| `maxLeverage` тира 0 → `l(tier0) = 1/(2·maxLeverage)` | `meta` [L1], [D9] | 40→0.0125, 25→0.02, 20→0.025, 10→0.05, 5→0.1, 3→0.16667 (HIP-3 `xyz` также 50→0.01, 30→0.016667) |
| Границы тиров, `maintenance_deduction` | `marginTables` или неявная таблица [L1], [D9] | tier1 начинается с 3M / 20M / 40M / 70M / 100M / 150M USDC; неявные таблицы тиров не имеют |
| `szDecimals` | `meta` [L1] | 0…5 (BTC 5, ETH 4, SOL 2, XRP/DOGE 0) |
| Правило тика цены ордера | [D7] | ≤5 значащих цифр и ≤ `6 − szDecimals` знаков; целые разрешены всегда |
| Флаги режима маржи | `meta` [L1] | `onlyIsolated`/`marginMode` — на основном dex только у delisted; на HIP-3 `noCross`/`strictIsolated` |
| `isDelisted` | `meta` [L1] | 56 из 234 |
| Максимальный размер market-ордера | [D12] | «$30,000,000 for max leverage >= 25, $5,000,000 for max leverage in [20, 25), $2,000,000 for max leverage in [10, 20), otherwise $500,000»; limit-ордер — ×10 |
| Funding impact notional | [D12] | «20000 USDC for BTC and ETH»; «6000 USDC for all other assets» |
| Состав oracle | `hl_liquidation_math.md` §1.5 (цитата `hypercore/oracle.md` от 2026-09-25, сегодня не перечитывалась); [D12] | у активов с основной ликвидностью вне Hyperliquid (BTC) oracle не включает спот Hyperliquid; «the only USDC-denominated perpetual contracts are PURR-USD and HYPE-USD» [D12] |

`PositionSizing` и `Leverage`/`MarginTable` в движке (BTC-константы) относятся к тому же списку — см. `multi_pair_architecture.md` §1, §3.

---

## 9. Расхождения кода с данными (факты чтения, код этим слайсом не менялся)

Ссылки `путь:строка` — по HEAD `0bc30ee` (так файлы читались). К концу слайса в рабочем дереве появились чужие незакоммиченные правки
(в том числе `engine/.../margin/LiquidationCalculator.java`); номера строк в нём могут отличаться от HEAD.

1. **`LiveRoundPrices.java:68-77`** — fallback-свеча: `coin` зашит `"BTC"`; окно `[atMs−60 с, atMs+60 с]`; `response.get(0)` — свеча
   `t = atMs − 60 с` (выровненный `atMs`) или свеча на одну минуту раньше содержащей `atMs` (невыровненный) — проверено в §4.2
   [L6]. `o` этой свечи — цена на минуту раньше начала раунда.
2. **`engine/.../market/Candle.java:32-42`** — OHLC округляется до `scale 2`; затем `low.signum() <= 0` → `IllegalArgumentException`
   («low must be positive»). `LiveRoundPrices.java:43` строит `Candle` из каждого тика. У 13 из 178 живых активов `markPx <
   0.005` (§3.5): `0.00` → исключение. У DOGE `0.094534` → `0.09` (−4.80%); XRP `1.4964` → `1.50` (+0.24%); SUI `1.1696` →
   `1.17`; PUMP `0.005746` → `0.01` (+74%) [L11].
3. **`engine/.../margin/LiquidationCalculator.java:31, 35`** — `MONEY_SCALE = 2` для цен и ликвидации; `MAINTENANCE_MARGIN_RATE` —
   константа BTC tier0. Для `l` другого актива и для цен меньше доллара шкала 2 мала (§3.5, §8).
4. **`LivePriceSampler.java:42, 51, 60-73`** — опрос `metaAndAssetCtxs` каждые 2 с (вес 20, §5.2) и поиск индекса по имени `"BTC"`; буфер на
   один актив (`MAX_TICKS = 7200`, строка 29). Расширение на N активов не требует дополнительных HTTP-вызовов (§3.1).
5. **`engine/.../round/PositionSizing.java:22`** — `SIZE_SCALE = 8` против `szDecimals` (BTC 5, ETH 4, SOL 2, XRP/DOGE 0) — уже
   отмечено в `multi_pair_architecture.md` §2.
6. **`tools/fetch_hl_candles.py:212`** — имя выходного файла `hl_{coin.lower()}_{interval}.csv`; для HIP-3 `--coin xyz:TSLA` в имени
   будет `:` (не запускалось). `INTERVAL_MS` в скрипте — только `1h`, `1d` (`tools/fetch_hl_candles.py:61-64`).

---

## 10. Выводы для механики данных (предложение — решить в неделю реализации)

Каждый пункт опирается на факты выше; это не зафиксированное решение.

1. **Живые тики по N активам.** Один REST-опрос `{"type":"metaAndAssetCtxs"}` без `dex`; `ctxs[i]` разбирать по имени из `universe[i]`; по
   буферу тиков на актив. `markPx` — источник для ликвидаций [D10] и для текущего `LivePriceSampler`. Период: интервал между
   обновлениями `markPx` наблюдался в пределах 1–3 с (§3.2), 2 с сопоставим с ним (часть обновлений может склеиваться) и стоит 600
   веса/мин (50% лимита) и ≈3.1 ГБ/сут; 3 с — 400 веса/мин, но реже интервала обновления: промежуточные значения теряются, а для
   проверки ликвидации по экстремумам (low/high пути) это важно.
2. **Если бюджет IP нужен под другое** (несколько стендов за одним IP, §5.2): WebSocket `fastAssetCtxs` (`markPx`+`midPx` всех монет, включая HIP-3,
   снимок + дельты) или `allDexsAssetCtxs`/`activeAssetCtx` — расходуют 1 соединение и 1 подписку из лимитов 10/1000, но формат и частота
   **UNVERIFIED вживую**: нужен отдельный проверочный прогон до опоры на него.
3. **`allMids` не заменяет `markPx`** для проверки ликвидаций и закрытия раунда (mid ≠ mark: медиана |Δ| 3.6–6.2 bp, максимум 24 bp в одном
   снимке; обновляется реже; нет `prevDayPx`/`dayNtlVlm`). Подходит для отображения плиток лобби, если допустима задержка 3–6 с.
4. **Свечи для закрытия раунда и fallback.** `candleSnapshot` по одному активу за вызов (пакетного вызова в доках нет — **UNVERIFIED**);
   окно `startTime = roundStart`, `endTime = roundEnd − 1` (обе границы по `t` включительные: с `endTime = roundEnd` придёт ещё и свеча
   следующей минуты); интервал `1m`, для раунда 3 мин — `3m` (сетка `t % 180000 == 0` — при окнах раундов, выровненных по эпохе кратно 180 с;
   тогда свеча раунда одна); запрос не раньше `roundEnd + ~2 с`;
   свеча финальна, если `T < now`; строку выбирать по `t`, а не `get(0)`. Для тихих активов пустой хвост — норма: брать `c` последней
   имеющейся строки (carry-forward) либо ticks. **Пересмотрено 2026-10-02 (второе ревью, F1):** `c` последней сделки не переносится
   вперёд как цена — это цена сделки, а тики — mark (§3.4), и плоский mark рассчитывался как DOWN на закрытии сделки 27-минутной
   давности; пустой хвост тихого актива закрывается последним *живым* тиком при условиях правила тихого актива
   (`markets_architecture.md` §3.2 п. 5), а свечи — прошлое первого опроса и свидетельство «сделок не было» (§13.3).
   **Дополнено 2026-10-02 (H1):** свечи — ещё и мост через дыру между двумя *живыми* тиками, целиком или никак: каждая минута дыры
   торговалась, метки по краям дыры лежат внутри диапазона своей минуты (допуск 15 bp), дыра не длиннее 30 минут (§13.4, §13.5).
5. **Свечи — цены сделок, не mark** (`n` — число сделок, `v` — объём, §4.1): для проверки ликвидации оставлять путь из mark-тиков; свечи
   использовать для цены на границах раунда и для сверки.
6. **Параметры на актив для хранения** (ключ — `name`, не индекс): `name`, `dex` (пусто либо префикс до `:`), `universeIndex` (кэш, перепроверять), `szDecimals`,
   `maxLeverage` (= `maxLeverage` тира 0), `marginTableId` + тиры (явная таблица из `marginTables` либо неявная `[0,∞) → id`), производные `l(tier0)` и
   `deduction(tierN)`, `priceDecimals = 6 − szDecimals`, `isDelisted`, `onlyIsolated`/`marginMode`, `collateralToken` (для HIP-3).
7. **Обновление параметров**: `meta` приходит внутри каждого ответа `metaAndAssetCtxs` — отдельный `meta` не нужен; хеш
   `(name, szDecimals, maxLeverage, marginTableId, isDelisted, marginTables)` позволяет заметить смену тиров/листинг/делистинг без дополнительных запросов.
8. **Шкала цен и размеров**: цены хранить минимум с 6 знаками (до `6 − szDecimals` знаков у `markPx`), размеры — `szDecimals` актива; шкала 2 из
   `Candle`/`LiquidationCalculator` не представляет 25 активов с ценой < $0.01 (§9).
9. **Список активов для лобби**: отфильтровать `isDelisted`; у delisted `markPx` заморожен, `midPx = null`. Порог по `dayNtlVlm` сокращает список: ≥$10M — 27
   активов, ≥$1M — 82, ≥$100k — 160 из 178. У активов с малым оборотом отсутствуют свечи хвостовых минут (§4.3), но `markPx` в `ctx` присутствует у всех
   живых (null-ов нет в основном dex и в `xyz`).

---

## 11. Что не проверено

- Поведение при превышении лимита (код ответа, тело, длительность блокировки), тип окна (скользящее/фиксированное), формула надбавки веса `candleSnapshot` за
  «каждые 60 строк» (floor или ceil), вес вызовов с `dex`. Превышение лимита намеренно не провоцировали.
- WebSocket: подключение не открывалось; форматы `allMids`/`activeAssetCtx`/`candle`/`fastAssetCtxs`/`allDexsAssetCtxs`, частота, задержка, признак закрытия свечи — только
  по доке.
- Время финализации закрытой минуты измерено один раз на BTC (одна граница минуты, 7 опросов); для менее ликвидных активов позднее изменение закрытой
  свечи не измерялось. Часы клиента сверены с `Date` ответа с точностью до секунды.
- Почему в одном ответе 5058, а не 5000 строк; единый ли потолок для `3m`, `1h`, `1d` (в `hl_liquidation_math.md` для `1h` — «~5000 часов»).
- Распределение интервалов между обновлениями `markPx` (8 с наблюдений) и `allMids` (~10 с); причина более редкого обновления `allMids`.
- Стабильность индекса в `universe` во времени (только наблюдения за 09-25…10-01), порядок добавления новых активов.
- Вне торговых часов: `markPx`, свечи и `isDelisted`/halt у HIP-3-активов (акции, индексы, FX, сырьё) в выходные/ночью; вес и скорость HIP-3-вызовов.
- HIP-4 (outcome-рынки): 520 ключей `#N` в `allMids` и описание в [D5], [D8], [D12]; `outcomeMeta`/`outcomeMetaUpdates` не запрашивались — какие это события и
  категории (в том числе спорт/политика), неизвестно.
- Сжатие: единичная проверка (`Accept-Encoding: gzip`, один вызов) — `Content-Encoding` не вернулся; для других типов запросов не проверялось.
- Задержки и скорость ответов измерены из одной точки; для VPS/the bench boxа могут отличаться.
- Торговые комиссии, минимальный ордер ($10 по [D16]) и прочие параметры исполнения не изучались (для paper-trading не нужны).

---

## 12. Источники и сырые файлы

### 12.1 Страницы доков (все fetched 2026-10-01, `https://hyperliquid.gitbook.io/hyperliquid-docs/…`)

| ID | Путь | Что взято |
|---|---|---|
| D1 | `for-developers/api/rate-limits-and-user-limits.md` | лимиты IP, веса, WS-лимиты |
| D2 | `for-developers/api/info-endpoint.md` | `allMids`, `candleSnapshot` (5000, интервалы), пагинация |
| D3 | `for-developers/api/info-endpoint/perpetuals.md` | `perpDexs`, `meta`, `metaAndAssetCtxs`, `allPerpMetas`, `perpCategories` |
| D4 | `for-developers/api/websocket.md` | URL, переподключение |
| D5 | `for-developers/api/websocket/subscriptions.md` | типы подписок и форматы данных |
| D6 | `for-developers/api/websocket/timeouts-and-heartbeats.md` | 60 с, ping/pong |
| D7 | `for-developers/api/tick-and-lot-size.md` | правила цены и размера |
| D8 | `for-developers/api/asset-ids.md` | индексы, HIP-3 имена, outcome-кодировка |
| D9 | `trading/margin-tiers.md` | формулы тиров, неявные таблицы (<50), таблицы mainnet |
| D10 | `trading/liquidations.md` | формула `liq_price`, mark price для ликвидации |
| D11 | `trading/margining.md` | режимы маржи, плечо 1…max |
| D12 | `trading/contract-specifications.md` | funding impact notional, max market order, recurring outcomes |
| D13 | `trading/perpetual-assets.md` | диапазон maxLeverage 3–40x |
| D14 | `trading/robust-price-indices.md` | состав mark price, период обновления ~3 с |
| D15 | `hyperliquid-improvement-proposals-hips/hip-3-builder-deployed-perpetuals.md` | роли deployer, settlement, oracle |
| D16 | `for-developers/api/error-responses.md` | список ошибок ордеров; поведения при превышении лимита нет |

Индекс доков: `llms.txt` (fetched 2026-10-01). Предыдущие проверки тех же страниц — `docs/research/hl_liquidation_math.md` §1 (2026-09-25).

### 12.2 Живые запросы L1…L17 — таблица в §1; сырые ответы — каталог `…/scratchpad/hyperliquid/` (файлы перечислены в §1).
Скрипты опроса (`hl.py`) и копии страниц доков (`docs/`) — там же.

---

## 13. Измерения 2026-10-02 (срез «фиксы пайплайна»)

Только факты; три запроса `POST https://api.hyperliquid.xyz/info` без ключей, 2026-10-01 23:07 UTC (= 2026-10-02 01:07 по месту; дата проекта — 2026-10-02), с той же машины, что и §1. Сырые ответы — в рабочем каталоге агента (`g5_hl_*.json`, вне репозитория).

### 13.1 `endTime` на границе минуты (L18, L19)

Граница B = 2026-10-01 22:57:00 UTC (`1790895420` с), окно с `startTime = B − 180 с`:

| Запрос | Строки ответа (`t`) | Последняя строка |
|---|---|---|
| `endTime = B` (мс) | B−180 с, B−120 с, B−60 с, **B** (4 строки) | свеча, начинающаяся в B: `t=1790895420000`, `T=1790895479999`, `o="84725.0"`, `c="84702.0"` |
| `endTime = B − 1` мс | B−180 с, B−120 с, B−60 с (3 строки) | свеча минуты перед границей: `t=1790895360000`, `T=1790895419999`, `o="84727.0"`, `c="84725.0"` |

- Граница `endTime` по `t` включительная (как в §4.1, L6): с `endTime = B` приходит и свеча, открывающаяся в B, то есть строка со сделками **после** границы. С `endTime = B − 1` ответ заканчивается минутой перед границей, чей `c` — последняя сделка до B.
- В этом образце `c` предыдущей минуты (84725.0) и `o` следующей (84725.0) совпали; в образце 2026-10-01 18:00/18:01 они различались на 1 USD (`c=84776.0` → `o=84777.0`, фикстура `server/src/test/resources/fixtures/hyperliquid/candle_snapshot_btc_1m.json`, обрезана из L-образцов 2026-10-01) — равенства ждать нельзя.
- Следствие для кода: история для границы раунда запрашивается с `endTime = конец − 1`, цена на границе — `c` минуты перед ней, не `o` минуты после (`HyperliquidPriceSource`, класс-комментарий «Boundary convention»).

### 13.2 `isDelisted` в `metaAndAssetCtxs` (L20)

`{"type":"metaAndAssetCtxs"}` в 23:07:32 UTC, ответ 72 218 байт за 0.34 с:

- `universe` 234 записи, `ctxs` 234, из них `isDelisted: true` — **56** (те же числа, что 2026-10-01, §0 п. 1).
- У живого актива (BTC, индекс 0) ключи записи: `marginTableId`, `maxLeverage`, `name`, `szDecimals` — **ключа `isDelisted` нет вовсе**; у делистнутых он `true`.
- У делистнутых `ctx.markPx` присутствует и заморожен (`MATIC` 0.37621, `RNDR` 6.8952, `FTM` 0.71688), `midPx: null` (как в §10 п. 9).
- Что делает код: опрос читает `isDelisted` каждые 3 с даром (тот же ответ) и сообщает каталогу; рынок такого актива закрывается, когда не меньше трёх успешных ответов подряд говорят «делистнут», первый из них был не меньше 10 с назад, а последний не старше 15 с (`HyperliquidCatalog.watch`; неудавшийся опрос или ответ с живым активом начинает счёт заново), причина для игроков — «The asset was delisted at the source».

### 13.3 Свечи тихих активов и `n` (L21, L22), 2026-10-02 03:17 UTC

Два запроса `{"type":"candleSnapshot","req":{"coin":…,"interval":"1m","startTime":now−1ч,"endTime":now}}` в 03:17:01 UTC (= 05:17 по месту): `xyz:BRENTOIL` и `NOT` (тот же тихий актив, что в §4.3), 200 за 0.32 и 0.37 с.

| Актив | Строк | С `n > 0` | Заполнителей (`n = 0`) | Хвост |
|---|---|---|---|---|
| `xyz:BRENTOIL` | 60 | 60 | 0 | последняя строка — минута перед запросом (`T` на 1.2 с раньше `now`: закрытая), текущей минуты нет (первой сделки в ней ещё не было) |
| `NOT` | 59 | 12 | **47** (`n=0`, `v="0.0"`, `o=h=l=c=0.000499` — закрытие предыдущей строки) | `n=2` и `n=1` у двух последних строк: заполнители стоят *между* строками со сделками |

- У каждой строки есть `n` (число сделок): ключи `T, c, h, i, l, n, o, s, t, v`. Заполнитель — это `n=0`; цена в нём — не сделка, а повтор предыдущего закрытия (как в §4.3). Код (`HyperliquidPriceSource`) строки без сделок пропускает; строка без поля `n` считается строкой со сделками.
- Открытая свеча: строка текущей минуты приходит с `T` в будущем и меняющимися `h`/`l`/`c` (§4.1); «закрыта» — `T < now`. Засев нового рынка берёт только закрытые свечи, чтобы не выставить `h` минуты в процессе как свежую цену.
- Вес `candleSnapshot` для расчёта засева: 20 + 1 на каждые 60 строк ответа (§5.1), формула округления **UNVERIFIED** — в коде вверх. Окно засева на стенде (15 мин + 30 мин просмотра назад) — 45 строк, вес 21; при суточном `DAILY` (24 ч + 30 мин) — 1470 строк, вес 45. Это числа расчёта (`HistorySeeder`, класс-комментарий), а не измерения: превышение лимита не провоцировали (§11).

### 13.4 Метка против свечей: допуск `herd.prices.hyperliquid.max-basis` (H1, 2026-10-02 05:02–05:08 UTC)

Вопрос: насколько метка (`markPx`) расходится со сделками (свечами) в тот же момент — от этого зависит, можно ли закрыть дыру между двумя живыми тиками свечами (`GapBridge`, `markets_architecture.md` §3.2 п. 5), и какой допуск ставить. Только факты; запросы read-only, с паузой 3–8 с (бюджет стенда: опрос и каталог — 640 из 1200 веса в минуту с этого же IP), сырые ответы — в рабочем каталоге агента (вне репозитория).

**Замер 1. `markPx` против последней закрытой свечи, 12 рынков по умолчанию** (`CatalogConfig`: BTC, ETH, SOL, XRP, DOGE, HYPE, `xyz:NVDA`, `xyz:TSLA`, `xyz:SP500`, `xyz:XYZ100`, `xyz:GOLD`, `xyz:BRENTOIL`). Два среза — 05:02:20 и 05:03:27 UTC, в каждом по одному `metaAndAssetCtxs` на dex (основной и `xyz`) и по одному `candleSnapshot` 1m на монету (28 запросов); свечи читались через 0–37 с после `markPx` (12 запросов подряд через 3 с), поэтому возраст закрытой свечи в таблице — на момент её запроса. Пятница, 05:02 UTC: сессия акций и индексов США закрыта (обычное расписание NYSE, открытие в 13:30 UTC, — не из источников проекта, **UNVERIFIED**), поэтому у `xyz:NVDA`/`TSLA` 2…35 сделок в минуту.

| Рынок | markPx 05:02:20 | close последней закрытой свечи со сделками | Δ, bp | markPx 05:03:27 | close последней закрытой свечи | Δ, bp | markPx внутри [low, high] свечи идущей минуты (05:02 / 05:03) |
|---|---|---|---|---|---|---|---|
| BTC | 86619 | 86624 (20 с назад, n=397) | +0,58 | 86565 | 86573 (28 с назад, n=178) | +0,92 | да (86605…86625) / да (86565…86578) |
| ETH | 2742.4 | 2742.4 (24 с назад, n=118) | 0,00 | 2740.2 | 2740.3 (31 с назад, n=143) | +0,36 | да (2741.6…2742.5) / да (2739.9…2740.7) |
| SOL | 123.46 | 123.49 (27 с назад, n=147) | +2,43 | 123.23 | 123.3 (35 с назад, n=197) | +5,68 | да (123.35…123.49) / да (123.2…123.31) |
| XRP | 1.529258 | 1.5294 (30 с назад, n=39) | +0,93 | 1.5268 | 1.5271 (38 с назад, n=94) | +1,96 | да (1.5283…1.5294) / да (1.5267…1.5272) |
| DOGE | 0.096689 | 0.096697 (34 с назад, n=22) | +0,83 | 0.096574 | 0.096596 (41 с назад, n=36) | +2,28 | да (0.096603…0.096701) / да (0.09657…0.096597) |
| HYPE | 89.3355 | 89.319 (37 с назад, n=430) | −1,85 | 89.271 | 89.284 (45 с назад, n=229) | +1,46 | да (89.276…89.347) / да (89.228…89.309) |
| xyz:NVDA | 232.12 | 232.15 (40 с назад, n=14) | +1,29 | 232.01 | 232.03 (48 с назад, n=9) | +0,86 | да (232.06…232.15) / да (232.01…232.02) |
| xyz:TSLA | 356.6 | 356.63 (44 с назад, n=35) | +0,84 | 356.54 | 356.59 (51 с назад, n=4) | +1,40 | да (356.58…356.6) / да (356.54…356.54) |
| xyz:SP500 | 7696.4 | 7696.5 (47 с назад, n=40) | +0,13 | 7695.2 | 7695.1 (55 с назад, n=17) | −0,13 | да (7695.6…7697) / да (7695…7695.2) |
| xyz:XYZ100 | 30670 | 30669 (50 с назад, n=19) | −0,33 | 30661 | 30661 (58 с назад, n=38) | 0,00 | да (30661…30674) / да (30660…30663) |
| xyz:GOLD | 4185.7 | 4186.2 (54 с назад, n=15) | +1,19 | 4185.2 | 4184.6 (1 с назад, n=10) | −1,43 | да (4185.6…4186.2) / свечи идущей минуты ещё нет |
| xyz:BRENTOIL | 102.19 | 102.14 (57 с назад, n=12) | −4,89 | 102.01 | 102.02 (5 с назад, n=29) | +0,98 | да (102.11…102.19) / свечи идущей минуты ещё нет |

Δ = (close закрытой свечи − markPx) / markPx, в bp (1 bp = 0,01%). Итог по 24 наблюдениям: |Δ| не больше 5,68 bp (медиана 0,98 bp); метка лежала вне диапазона [low, high] последней *закрытой* минуты в 10 из 24 случаев, не дальше чем на 3,91 bp (метка успевает уйти за минуту, пока читается следующая). Метка внутри [low, high] свечи *идущей* минуты — в 22 из 22 случаев (у двух рынков в срезе 05:03 такой свечи ещё не было), хотя |метка − последняя сделка той же минуты| доходила до 8,89 bp (медиана 1,50 bp).

**Замер 2. Все метки стенда против свечи своей минуты.** Метки взяты из таблицы `price_snapshots` работающего стенда (`SELECT` из `herd-db-1`, ничего не менялось): 7 476 меток двенадцати рынков (6 в `hyperliquid` через 3 с, 6 в `hyperliquid:xyz` через 6 с), 04:23:09–05:04:44 UTC, 41,6 мин — это метки, записанные сервером (момент получения ответа), не запросы этого среза. Свечи 1m тех же двенадцати монет за тот же интервал — 12 запросов `candleSnapshot` в 05:08 UTC. Для каждой метки взята закрытая свеча со сделками минуты, в которой лежит время метки (метки минут без сделок — 60 из 7 536 — пропущены), и посчитаны в bp от метки: расстояние до диапазона [low, high] (0 внутри) и расстояния до точек, с которыми можно было бы сравнивать «по точкам»: open свечи, close предыдущей свечи со сделками, close свечи.

| Рынок | меток | до диапазона [low, high] своей минуты, bp: p95 / p99 / max | \|метка − close предыдущей свечи со сделками\|, bp: p50 / p95 / p99 / max |
|---|---|---|---|
| BTC | 837 | 0,00 / 0,14 / 2,43 | 2,20 / 10,17 / 13,99 / 23,22 |
| ETH | 837 | 0,00 / 0,73 / 1,82 | 2,55 / 9,84 / 13,86 / 18,61 |
| SOL | 837 | 0,00 / 0,82 / 1,64 | 4,10 / 20,27 / 33,20 / 45,35 |
| XRP | 837 | 0,61 / 1,96 / 2,63 | 3,53 / 15,70 / 27,60 / 34,80 |
| DOGE | 837 | 1,77 / 3,41 / 5,16 | 4,15 / 16,80 / 24,37 / 29,71 |
| HYPE | 837 | 0,00 / 0,00 / 1,48 | 3,02 / 14,89 / 21,10 / 26,23 |
| xyz:NVDA | 409 | 1,29 / 1,73 / 2,16 | 0,86 / 3,45 / 4,74 / 6,47 |
| xyz:TSLA | 379 | 1,68 / 2,53 / 2,53 | 0,84 / 2,81 / 4,49 / 5,33 |
| xyz:SP500 | 419 | 0,26 / 0,52 / 0,78 | 0,26 / 1,17 / 2,21 / 2,60 |
| xyz:XYZ100 | 419 | 0,33 / 0,33 / 0,65 | 0,33 / 1,96 / 2,94 / 3,59 |
| xyz:GOLD | 419 | 1,19 / 1,67 / 2,15 | 0,72 / 3,10 / 3,82 / 5,26 |
| xyz:BRENTOIL | 409 | 0,98 / 1,96 / 2,94 | 0,98 / 6,85 / 9,80 / 10,78 |
| **все двенадцать** | 7 476 | 0,84 / 1,91 / **5,16** | 1,85 / 13,08 / 21,22 / **45,35** |

Доля меток, которые проходят проверку при допуске b (все 12 рынков):

| b, bp | 2 | 3 | 5 | 7,5 | 10 | 15 | 20 | 30 |
|---|---|---|---|---|---|---|---|---|
| метка внутри [low, high] своей минуты, расширенного на b | 99,4% | 99,8% | 100% | 100% | 100% | 100% | 100% | 100% |
| \|метка − open свечи\| ≤ b | 54,5% | 64,6% | 76,8% | 85,7% | 90,8% | 96,3% | 98,6% | 99,6% |
| \|метка − close предыдущей свечи\| ≤ b | 53,3% | 63,4% | 76,0% | 85,2% | 91,0% | 96,5% | 98,6% | 99,6% |
| \|метка − close свечи своей минуты\| ≤ b | 50,4% | 59,6% | 72,0% | 82,4% | 89,2% | 95,6% | 98,6% | 99,8% |

Что видно (факты замера):

- Метка лежит внутри диапазона [low, high] своей минуты с точностью до 5,16 bp (максимум по 7 476 меткам; 99,9-й перцентиль — 3,43 bp). Сравнение метки с одной точкой минуты (open, close соседней свечи) шумит на движение рынка за минуту: 95-й перцентиль 13 bp, 99-й — 21 bp, максимум 45 bp (SOL), и допуск в 15 bp отверг бы 3,5–4,4% меток при том, что метка и сделки согласны.
- Расстояние от входа до цены ликвидации при плече 40x: `l = 1/(2·40) = 0,0125`, дистанция `(1/40 − l)/(1 − l) ≈ 1,27%` для длинной и `(1/40 − l)/(1 + l) ≈ 1,23%` для короткой позиции (формула [D10], §8). Допуск 0,15% — около 12% этой дистанции.

**Что из этого взято в код (предложение — решить на ревью).** `herd.prices.hyperliquid.max-basis` по умолчанию `0.0015` (15 bp): втрое выше максимума замера (5,16 bp) и около 12% наименьшей дистанции до ликвидации (1,23%). Живая цена соседа дыры сравнивается с диапазоном [low, high] свечи своей минуты, а не с open/close соседней свечи (расстояние до диапазона — 0…5,16 bp, до точки — до 45 bp). Это замер одного спокойного получаса пятничной ночи (вне сессии США); на движении рынка (данные США, ФРС) метка и сделки могут расходиться сильнее — тогда проверка откажет, а раунд останется дырой и вернётся ставками, не расплатится неверной ценой (**UNVERIFIED**: такой момент не измерялся).

### 13.5 Сколько минут торгуется каждый из двенадцати рынков (H1, 2026-10-02 05:07 UTC, последние 24 ч)

Двенадцать запросов `candleSnapshot` 1m на `[now − 24 ч, now]` (по 1441 строке, вес 44–45: округление надбавки **UNVERIFIED**, §5.1; пауза 8 с). «Окно целиком со сделками» — 20 подряд идущих минут, в каждой из которых есть свеча с `n > 0` (скользящее окно с шагом в минуту): столько минут нужно мосту для дыры в 20 минут.

| Рынок | минут со сделками из 1440 | самая длинная серия минут без сделок | 20-минутных окон целиком со сделками | движение close→close за минуту, bp: p50 / p95 / p99 / max | размах свечи (h−l)/c, bp: p50 / p95 / max |
|---|---|---|---|---|---|
| BTC | 1440 (100,0%) | 0 мин | 100,0% | 2,7 / 11,0 / 18,3 / 41,0 | 4,7 / 14,9 / 56,5 |
| ETH | 1440 (100,0%) | 0 мин | 100,0% | 3,0 / 12,3 / 21,1 / 44,0 | 5,5 / 16,7 / 61,8 |
| SOL | 1440 (100,0%) | 0 мин | 100,0% | 4,2 / 16,1 / 26,4 / 54,0 | 7,6 / 20,4 / 57,0 |
| XRP | 1432 (99,4%) | 1 мин | 90,7% | 4,0 / 17,4 / 27,5 / 44,3 | 6,7 / 20,7 / 57,3 |
| DOGE | 1416 (98,3%) | 1 мин | 77,0% | 5,0 / 18,6 / 29,8 / 63,5 | 5,7 / 21,2 / 73,0 |
| HYPE | 1440 (100,0%) | 0 мин | 100,0% | 5,5 / 19,0 / 27,9 / 53,3 | 10,6 / 25,3 / 57,0 |
| xyz:NVDA | 1417 (98,4%) | 2 мин | 81,7% | 1,7 / 9,1 / 17,4 / 53,7 | 3,0 / 10,9 / 57,3 |
| xyz:TSLA | 1377 (95,6%) | 2 мин | 60,0% | 2,2 / 10,9 / 20,8 / 52,5 | 2,2 / 14,2 / 55,6 |
| xyz:SP500 | 1439 (99,9%) | 1 мин | 98,6% | 0,9 / 4,8 / 7,7 / 17,6 | 1,7 / 7,1 / 18,4 |
| xyz:XYZ100 | 1440 (100,0%) | 0 мин | 100,0% | 1,6 / 6,6 / 10,6 / 24,6 | 2,6 / 9,6 / 22,0 |
| xyz:GOLD | 1425 (99,0%) | 2 мин | 86,0% | 1,2 / 6,9 / 10,6 / 23,0 | 1,2 / 6,7 / 31,8 |
| xyz:BRENTOIL | 1435 (99,7%) | 1 мин | 93,0% | 3,9 / 16,0 / 27,4 / 62,3 | 6,8 / 19,9 / 67,9 |

- За сутки (в них входят и часы, когда сессия США обычно открыта) каждый из двенадцати рынков торговался в 95,6–100% минут; ни одной минуты без сделок — у BTC, ETH, SOL, HYPE и `xyz:XYZ100`. Самая длинная серия минут без сделок — 2 минуты.
- Окно в 20 минут целиком со сделками: у пяти рынков — 100%, у `xyz:TSLA` — 60% (вне сессии США: в 41-минутном окне замера 2 у него 4 минуты без сделок, на них пришлись 40 из 419 меток). Дыра в 2 минуты требует трёх подряд минут со сделками — для всех двенадцати это почти всегда так (самая длинная пауза — 2 минуты).
- Замер не говорит о выходных и ночи у акций: суббота и воскресенье не измерялись (**UNVERIFIED**; `hl_multi_asset_live.md` §7.4, §11).
