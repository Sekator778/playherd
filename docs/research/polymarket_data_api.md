# Polymarket: можно ли брать реальные шансы из публичного API — факты для пар «Политика / Спорт / Крипто»

Дата: **2026-10-01** (живые запросы 19:32–19:47 UTC). Задача среза: дать сессия твёрдые факты об источнике данных до того, как она спроектирует пары с «ценой = живая вероятность рынка Polymarket» (ставка вверх/вниз с плечом внутри раунда). Только факты об источнике; решения о продукте здесь не принимаются.

## Метод и обозначения

- **Живые запросы** — `curl`, только чтение, без ключей/логина/подписи, последовательно с паузами 2–4 с: **62 запроса к API** (26 CLOB, 26 Gamma, 10 data-api) за ~14 минут; **58 × HTTP 200**, 2 × 404 (ожидаемо: `/book` и `/midpoint` закрытого токена), 2 × 422 (мои неверные параметры: `order=volume_num`, `recurrence=15m`). Каждый запрос — строка в `requests.log` (время UTC, код, задержка, размер, файл, точная команда). Лимиты из доков не приближались (самый частый эндпойнт — 5 запросов за 16 с).
- **Официальные доки** — `docs.polymarket.com`, 34 страницы в виде markdown (`<страница>.md`, Mintlify), скачаны 2026-10-01 и лежат рядом с образцами. Источник каждого утверждения указан ссылкой на страницу.
- Образцы и логи: `<scratchpad агента>/polymarket/` (индекс файлов — `SAMPLES.txt`; ниже файлы названы по имени). Каталог в `/private/tmp` — не в репозитории.
- Пометки: **[живьём]** — получено запросом 2026-10-01, указан файл; **[док]** — написано в доках (URL), живой проверки нет; **[вывод]** — моя интерпретация наблюдений, Polymarket этого не заявляет; **UNVERIFIED** — не видел.
- Правило проекта о фактах (`CLAUDE.md` «Правила фактов»): числа ниже — из ответов API/доков; чего не видел — отмечено в §9.

## 0. Коротко (всё из разделов ниже)

1. С этой машины `gamma-api`, `clob`, `data-api` отвечают 200 без ключа; сообщений о гео-блокировке нет (§1).
2. Каталог: Gamma `GET /markets/keyset?tag_id=<id>&closed=false&order=volume24hr&ascending=false&limit≤100` + `after_cursor` даёт топ активных рынков тега по объёму за сутки. Теги: **sports = 1, politics = 2, crypto = 21** (§2).
3. Живая цена токена: CLOB `POST /midpoints` — до 500 токенов в запросе (доки), ответ 0,08–0,09 с (10 токенов), **не кэшируется CDN**. Gamma (`outcomePrices`, `bestBid`, `bestAsk`) для этого не годится: CDN-кэш 300 с и пакетное обновление строки рынка (наблюдалось раз в ~2 мин, **[вывод]**) (§3).
4. История: `GET data-api/v2/prices-history` — минутные бакеты, 1-минутные данные есть минимум ~7 суток назад (проверено 6,96 сут; на 12 сут — пусто), последние ~5 минут закрытых бакетов отсутствуют, но серия заканчивается «точным тиком» (§4). Серия — мидпоинты стакана (§4.4).
5. Закрытый рынок: `/book` и `/midpoint` → **404**; в batch-ответах токен молча пропадает; Gamma: `closed:true`, `outcomePrices ["1","0"]`; итог — `/v2/resolutions` (`payouts`), в конце истории — точка расчёта (§5). По докам возможен расчёт 50/50.
6. Лимиты — по IP, через Cloudflare: `/midpoints` 500 запросов / 10 с, `/prices-history` 1000 / 10 с, `/v2/prices-history` 200 / 10 с (§6). Заявлений о праве использования данных в доках нет; ToS-страница не читана (§6, §9).
7. Крипто «Up or Down»: свои серии 5m / 15m / 4h / hourly / daily для BTC/ETH/SOL/XRP; 5m/15m/4h резолвятся по Chainlink TWAP 60 с, hourly/daily — по Binance (§7).
8. Спорт: у игр есть `startTime`/`gameStartTime`, у живых — `live`, `score`, `period`, `elapsed`; фильтр `live=true` работает; в начале игры стакан очищается (§8).

## 1. Доступность с этой машины

| Хост | Команда | HTTP | Время / размер | Что видно |
|---|---|---|---|---|
| gamma | `curl 'https://gamma-api.polymarket.com/events?limit=1&active=true&closed=false'` | 200 | 0,10 с, 27 043 Б | заголовки `deprecation: true`, `sunset: Fri, 01 May 2026 00:00:00 GMT`, `warning: 299 - "use /events/keyset"`, `cache-control: public, max-age=300` (`reach_gamma.json`, `.headers`) |
| clob | `curl 'https://clob.polymarket.com/'` | 200 | 0,13 с, 4 Б | тело `"OK"` |
| clob | `curl 'https://clob.polymarket.com/time'` | 200 | 0,13 с | тело `1790883144` (unix-секунды сервера) |
| data-api | `curl 'https://data-api.polymarket.com/'` | 200 | 0,15 с, 13 Б | `{"data":"OK"}` |
| data-api | `curl 'https://data-api.polymarket.com/trades?limit=1'` | 200 | 0,07 с, 865 Б | массив сделок (v1; в ответе есть идентификаторы пользователей — для Herd не нужны) |

- Гео-блок: сообщений нет, все 58 успешных ответов — обычные данные **[живьём]**. Заголовок `cf-ray: …-TXL` (точка Cloudflare Берлин) — запросы шли, судя по нему, из Германии **[вывод]**.
- По докам геоограничения касаются **размещения ордеров**: «Polymarket restricts order placement from certain geographic locations…»; Германия (DE) — в списке «Close-Only on Frontend and API» (`https://docs.polymarket.com/api-reference/geoblock.md`, 2026-10-01) **[док]**. Чтение данных этим не ограничено **[живьём]**. Эндпойнт `https://polymarket.com/api/geoblock` я не вызывал (вне разрешённых хостов) — классификация IP этой машины самим Polymarket **UNVERIFIED**.
- `Gamma /events` и `/markets` (старые, с offset) помечены заголовками `deprecation: true`, `sunset: Fri, 01 May 2026` — дата прошла, ответ всё ещё 200 **[живьём]** (`gamma_markets_legacy.json.headers`: `warning: 299 - "use /markets/keyset"`). Changelog 2026-04-10: «The existing `GET /markets` and `GET /events` endpoints remain available but will be deprecated in a future release. New integrations should use the keyset variants.» (`https://docs.polymarket.com/changelog/predictions.md`) **[док]**.

## 2. Каталог: категории → события → рынки

### 2.1 Теги (категории)

| Категория | slug | id | Запрос | Ответ |
|---|---|---|---|---|
| Спорт | `sports` | **1** | `GET https://gamma-api.polymarket.com/tags/slug/sports` | `{"id":"1","label":"Sports","slug":"sports","forceShow":false,…,"forceHide":true}` (`tag_sports.json`) |
| Политика | `politics` | **2** | `GET …/tags/slug/politics` | `{"id":"2","label":"Politics",…,"forceHide":true}` (`tag_politics.json`) |
| Крипто | `crypto` | **21** | `GET …/tags/slug/crypto` | `{"id":"21","label":"Crypto",…}` (`tag_crypto.json`) |

- Другие теги, встреченные внутри ответов: Geopolitics `100265`, Iran `78`, Trump `126` (события политики); Games `100639`, Soccer `100350`, UEFA Nations League `100816` (спорт); у крипто-событий слаги `crypto-prices`, `bitcoin`, `up-or-down`, `recurring`, `daily`, `1H`, `4h`, `15M`, `5M`.
- `GET /tags?is_carousel=true&limit=60` вернул **один** тег (`439`, `ai`) — по-видимому, флаг карусели не соответствует верхнему меню приложения **[вывод]** (`tags_carousel.json`). Эндпойнт, отдающий верхние категории приложения (Politics/Sports/Crypto/…) с id, в доках не найден; проверены только три выше. Остальные категории (finance, tech, culture, …) **UNVERIFIED**.
- Доки: теги — направленный граф, а не дерево (`/tags/slug/{slug}/related-tags`, `…/related-tags/tags`) (`https://docs.polymarket.com/market-data/discover-markets.md`) **[док]**.

### 2.2 Эндпойнты листинга (Gamma, base `https://gamma-api.polymarket.com`)

| Что | Запрос | Ответ | Проверка |
|---|---|---|---|
| Топ **рынков** тега по объёму за сутки | `GET /markets/keyset?tag_id=2&closed=false&order=volume24hr&ascending=false&limit=10` | `{"$schema":…,"markets":[…],"next_cursor":"…"}`; 10 рынков = 58–76 КБ | 200, 0,16–0,19 с (`markets_politics_top_try1.json`, `markets_crypto_top.json`, `markets_sports_top.json`) |
| Следующая страница | то же + `&after_cursor=<next_cursor>` | страница 2 без пересечений со страницей 1, порядок убывания `volume24hr` продолжается | 200 (`markets_politics_top_page2.json`) |
| Топ **событий** тега | `GET /events/keyset?tag_id=2&closed=false&order=volume24hr&ascending=false&limit=5` | `{"events":[…],"next_cursor":"…"}`; событие несёт **все** свои рынки (32–128 шт.), 5 событий = **1,31 МБ** | 200 (`events_politics_top.json`) |
| Только moneyline в спорте | `GET /markets/keyset?tag_id=1&closed=false&order=volume24hr&ascending=false&limit=10&sports_market_types=moneyline` | как выше | 200 (`markets_sports_moneyline_top.json`) |
| Идущие игры | `GET /events/keyset?tag_id=1&live=true&closed=false&order=volume24hr&ascending=false&limit=6` | 6 событий, у всех `live:true` (432 КБ) | 200 (`events_sports_live.json`) |
| Рынки по токенам | `GET /markets/keyset?clob_token_ids=<t1>&clob_token_ids=<t2>…&limit=20` | 10 рынков по 10 токенам | 200, `cf-cache-status: MISS` (`fresh_gamma_by_tokens.json`) |
| Недавно закрытые | `GET /markets/keyset?closed=true&tag_id=1&order=closedTime&ascending=false&limit=6&sports_market_types=moneyline` | закрытые за последние минуты | 200 (`markets_closed_recent.json`) |
| По slug | `GET /events/slug/<slug>`; доки: `/markets/slug/<slug>`, `/markets/<id>`, `/events/<id>` | событие/рынок | `events/slug` 200; остальные **[док]** |
| Поиск | `GET /public-search?q=Bitcoin%20Up%20or%20Down&limit_per_type=5&events_status=active` | `{"events":[…],"pagination":{"hasMore":true,"totalResults":788}}` | 200 (`search_btc_updown.json`) |
| Виды спорта / типы линий | `GET /sports`; `GET /sports/market-types` | 473 вида `{id,sport,name,image,resolution,ordering,tags,primaryTagId,series,createdAt}`; `{"marketTypes":[…286 значений…]}` | 200 (`sports_list.json`, `sports_market_types.json`) |

Параметры keyset по доке (`https://docs.polymarket.com/api-reference/markets/list-markets-keyset-pagination.md`, `…/events/list-events-keyset-pagination.md`) **[док]**: `limit` 1–100 (по умолчанию 20; changelog 2026-05-14: максимум снижен до 100), `order` — список полей через запятую, `ascending` (по умолчанию `true`), `after_cursor`, `closed`, `tag_id` (массив), `liquidity_*`, `volume_*`, `end_date_min/max`, `start_date_*`; у событий — ещё `live`, `tag_slug`, `exclude_tag_id`, `series_id`, `game_id`, `recurrence`, `start_time_min/max`, `title_search`; у рынков — `clob_token_ids`, `condition_ids`, `sports_market_types`, `game_id`, `include_tag`. `offset` отвергается кодом 422. `next_cursor` присутствует, только если вернулось ровно `limit` элементов.

### 2.3 Поля рынка (Gamma market object; образец — `sample_gamma_market_binary_politics.json`)

| Поле | Тип в JSON | Пример | Замечание |
|---|---|---|---|
| `id` | строка | `"665374"` | id рынка в Gamma |
| `conditionId` | строка | `"0x5db999…6846"` | ключ для `/v2/resolutions`, аналитики |
| `slug`, `question` | строка | `will-the-us-invade-iran-before-2027` | slug — часть URL на polymarket.com |
| `outcomes` | **строка с JSON-массивом** | `"[\"Yes\", \"No\"]"` | метки бывают **не** Yes/No (§2.4) |
| `outcomePrices` | **строка с JSON-массивом** строк | `"[\"0.145\", \"0.855\"]"` | в живом рынке = мидпоинт `(bestBid+bestAsk)/2` (§3.5); в закрытом — итог `["1","0"]` |
| `clobTokenIds` | **строка с JSON-массивом** | `"[\"55115…9123\", \"19108…\"]"` | порядок = порядок `outcomes`; токен нужен для CLOB |
| `bestBid`, `bestAsk`, `spread`, `lastTradePrice` | число | `0.14`, `0.15`, `0.01`, `0.15` | **ключ `bestBid` отсутствует**, если в книге нет bid (2 из 10 рынков топа политики) |
| `oneHourPriceChange`, `oneDayPriceChange`, `oneWeekPriceChange` | число | `-0.01` | присутствуют не у всех |
| `volume24hr`, `volume1wk`, `volume1mo`, `volume1yr` | число | `497261.91…` | единица в доках не названа; сортировка `order=volume24hr` работает |
| `volume` / `volumeNum`; `liquidity` / `liquidityNum` | строка / число | `"70796700.33…"` / `70796700.33…` | дубли разного типа |
| `endDate`, `startDate`, `endDateIso`, `startDateIso` | строка ISO | `"2027-01-01T04:59:00Z"` | `startDate` рынка может быть на сутки раньше окна (§7) |
| `gameStartTime`, `closedTime` | строка **не-ISO** | `"2026-10-01 18:45:00+00"`, `"2026-10-01 19:36:36.105447+00"` | пробел вместо `T`, `+00` без минут, дробные секунды не всегда |
| `active`, `closed`, `acceptingOrders`, `archived`, `restricted`, `enableOrderBook` | bool | `true,false,true,false,true,true` | см. §5: после расчёта `active` остаётся `true` |
| `negRisk` (+ `negRiskMarketID` у группы) | bool | `false` | рынок входит в neg-risk группу взаимоисключающих исходов |
| `orderPriceMinTickSize`, `orderMinSize` | число | `0.01`, `5` | шаг цены рынка: наблюдались `0.01` и `0.001` |
| `groupItemTitle` | строка | `"Portugal"`, `"July 24"` | подпись исхода внутри события |
| `image`, `icon` | URL | `https://polymarket-upload.s3…jpg` | |
| `events[]` | массив | `[{id,slug,title,volume24hr,…}]` | родительское событие (у рынка из `/markets/keyset`) |
| `umaResolutionStatus`, `automaticallyResolved`, `resolvedBy`, `resolutionSource` | строка/bool | `"resolved"`, `true` | см. §5 |
| `feesEnabled`, `feeSchedule{rate,exponent,takerOnly,rebateRate}`, `feeType` | bool/объект | `true`, `{0.05,1,true,0.15}` | у спорта `sports_fees_v3` |
| `updatedAt` | строка ISO | `"2026-10-01T19:35:23.287252Z"` | время записи строки, см. §3.5 |
| **спорт:** `sportsMarketType`, `line`, `gameStartTime`, `secondsDelay`, `clearBookOnStart`; `gameId` — только у части рынков (есть в `events_sports_live.json`, нет у `unl-den-prt-2026-10-01-prt`) | | `"moneyline"`, `3.5`, `"2026-10-01 18:45:00+00"`, `1`, `true` | §8 |
| **крипто Up/Down:** `eventStartTime` | строка ISO | `"2026-10-01T19:30:00Z"` | начало окна измерения, §7 |

Поля события (Gamma event object): `id`, `slug`, `title`, `ticker`, `description`, `resolutionSource`, `startDate`, `endDate`, `image`/`icon`, `active`, `closed`, `liquidity`, `volume`, `volume24hr`/`volume1wk`/`volume1mo`/`volume1yr`, `openInterest`, `competitive`, `commentCount`, `negRisk`/`enableNegRisk`/`negRiskAugmented`/`negRiskMarketID`, `markets[]`, `tags[]` (`id,label,slug`), `series[]`, `seriesSlug`, `eventMetadata` (напр. `priceToBeat`, идентификаторы Optic Odds); у спорта — `live`, `ended`, `score`, `period`, `elapsed`, `startTime`, `eventDate`, `eventWeek`, `gameId`, `teams[]`, `sport{}` (§8). Полный список ключей — по `events_politics_top.json` и `sample_gamma_event_sports_live.json`.

### 2.4 Наблюдения по выборке (все [живьём])

- **Метки исходов разные.** `["Yes","No"]` (политика, крипто-«выше цены»), `["Up","Down"]` (крипто Up/Down), `["Over","Under"]` (тоталы), названия команд (`["Steelers","Browns"]`, `["Aurora","Team Liquid"]`, `["Ashley Lahey","Raquel Caballero Chica"]`). Доки (`market-data/market-details.md`): «Index `0` describes the YES outcome and index `1` describes the NO outcome» — для не-Yes/No рынков индекс 0 = **первая метка**, а не «YES».
- **Бинарность.** Во всех **498** разных рынках из сохранённых ответов (листинги и вложенные в события) ровно два исхода и два токена; метки: `Yes/No` — 388, `Over/Under` — 67, `Up/Down` — 8, имена команд/игроков — 35. Многоисходные события раскладываются на N бинарных рынков: «Brazil Presidential Election» — 32 рынка, «Presidential Election Winner 2028» — 128 (`events_politics_top.json`); футбольный матч 1X2 — 3 рынка (home / draw / away) в одном neg-risk событии (`sample_gamma_event_sports_live.json`).
- **Хвостовые цены среди «самых объёмных».** Доля рынков с `outcomePrices[0] < 0.02` или `> 0.98` в выборках топ-10 по `volume24hr`: политика, стр. 1 — **4 из 10** (0,0015; 0,0005; 0,0025; 0,0005); политика, стр. 2 — **7 из 10**; крипто — 2 из 10 (0,9995; 0,9915); спорт (все типы) — 2 из 10; спорт moneyline — 2 из 10. Рынков в диапазоне 0,05–0,95: 6, 1, 4, 8, 8 из 10 соответственно (`markets_*_top*.json`).
- **Закрытые рынки внутри незакрытого события.** `GET /events/keyset?closed=false` отдаёт событие с вложенным `markets[]`, где часть рынков уже `closed:true` и `outcomePrices ["0","1"]` (событие «US announces end of Iranian blockade by…?», `events_politics_top.json`). Фильтровать нужно на уровне рынка.
- **Размер полезной нагрузки.** Рынок ≈ 7 КБ JSON; событие с 128 рынками — сотни КБ.

### 2.5 Расхождения доков и реальности (проверено [живьём])

| Что написано | Что на самом деле |
|---|---|
| `GET /markets/keyset`: пример `order` — `volume_num,liquidity_num` (OpenAPI) | `order=volume_num` → **422** `{"type":"validation error","error":"order fields are not valid"}`; `order=volume24hr` → 200 (`markets_politics_top_try2.json`) |
| `market-data/prices-order-books.md`: «`BUY` returns the lowest ask… `SELL` returns the highest bid» | реально `side=BUY` → **лучший bid**, `SELL` → **лучший ask** (5 из 5 токенов, §3.3). Страница API-референса `get-market-price` пишет так же, как факт: «Returns the best bid price for BUY side or best ask price for SELL side» |
| пример ответа `/prices`: `"BUY": 0.08` (число) | значения — **строки**: `"BUY":"0.14"` |
| `/series?recurrence=…` (пример `weekly`) | `recurrence=15m` → 422 `expected value to be one of "daily, weekly, monthly, annual, hourly"` — серии 5m/15m/4h так не перечислить (§7) |
| changelog 2026-09-04: «`GET /v2/prices-history` replaces the CLOB-hosted route» | CLOB `GET /prices-history` на 2026-10-01 всё ещё отвечает 200 (§4) |

## 3. Живая цена: CLOB (base `https://clob.polymarket.com`, без ключа)

### 3.1 Эндпойнты

| Эндпойнт | Запрос | Ответ | Макс. элементов | Лимит (IP) | Проверка |
|---|---|---|---|---|---|
| `POST /midpoints` | тело `[{"token_id":"…"},…]`, `Content-Type: application/json` | `{"<token>":"0.145",…}` — строки | 500 [док] | 500 / 10 с [док] | 10 токенов: 889 Б, 0,08 с (`clob_midpoints_10.json`) |
| `POST /prices` | `[{"token_id":"…","side":"BUY"},{"token_id":"…","side":"SELL"},…]` | `{"<token>":{"BUY":"0.14","SELL":"0.15"},…}` | 500 [док] | 500 / 10 с [док] | 20 пар: 1096 Б, 0,08 с (`clob_prices_10x2.json`) |
| `POST /books` | `[{"token_id":"…"},…]` | массив `{market, asset_id, timestamp, hash, bids[], asks[], min_order_size, tick_size, neg_risk, last_trade_price}` | 500 [док] | 500 / 10 с [док] | 5 токенов: 20 КБ, 0,09 с (`clob_books_5.json`) |
| `POST /last-trades-prices` | `[{"token_id":"…"},…]` | `[{"price":"0.15","side":"BUY","token_id":"…"},…]` | 500 [док] | в таблице лимитов нет | 10 токенов: 1220 Б (`clob_last_trades_10.json`) |
| `GET /midpoint?token_id=` ; `/price?token_id=&side=` ; `/book?token_id=` ; `/last-trade-price?token_id=` ; `/spread?token_id=` | query | `{"mid":"0.085"}`; `{"price":"0.08"}`; книга; `{"price":"0.08","side":"SELL"}`; `{"spread":"0.01"}` | 1 | 1500 / 10 с [док] | `/book`, `/midpoint`, `/last-trade-price` — на закрытом токене, §5 |
| `GET /time` | — | unix-секунды | — | — | 200 |

Источник форматов и лимитов: `https://docs.polymarket.com/market-data/prices-order-books.md`, `https://docs.polymarket.com/api-reference/rate-limits.md` (2026-10-01). Форматы одиночных GET-вариантов — **[док]**, вживую проверен только `/last-trade-price` (на закрытом токене, §5) и 404 у `/book`, `/midpoint`; query-варианты batch (`GET …?token_ids=…`) не проверял.

### 3.2 Реальный образец: 10 токенов (YES-токены; запросы 19:37:42–19:37:46, Gamma — 19:39:17)

| Категория | slug рынка | YES-токен | `/midpoints` | `/prices` BUY (=bid) | `/prices` SELL (=ask) | `/last-trades-prices` | tick | Gamma `outcomePrices[0]` |
|---|---|---|---|---|---|---|---|---|
| politics | `will-the-us-invade-iran-before-2027` | `551150…9123` | 0.145 | 0.14 | 0.15 | 0.15 BUY | 0.01 | 0.145 |
| politics | `2026-balance-of-power-r-senate-r-house-537` | `103704…3320` | 0.075 | 0.07 | 0.08 | 0.08 BUY | 0.01 | 0.075 |
| politics | `will-geraldo-alckmin-win-the-2026-brazilian-presidential-e…` | `700591…5609` | 0.0015 | 0.001 | 0.002 | 0.001 SELL | 0.001 | 0.0015 |
| politics | `will-flvio-bolsonaro-win-the-2026-brazilian-presidential-e…` | `109876…1427` | 0.6205 | 0.62 | 0.621 | 0.621 BUY | 0.001 | 0.6205 |
| crypto | `bitcoin-above-74k-on-october-2-2026` | `510982…3245` | 0.9995 | 0.999 | 1 | 0.999 BUY | 0.001 | 0.9995 |
| crypto | `will-bitcoin-dip-to-70000-by-december-31-2026-from-august-…` | `261810…7732` | 0.335 | 0.33 | 0.34 | 0.33 SELL | 0.01 | 0.335 |
| crypto | `bitcoin-above-82k-on-october-2-2026` | `429285…3502` | 0.9625 | 0.96 | 0.964 | 0.968 BUY | 0.001 | 0.962 |
| sports | `unl-den-prt-2026-10-01-prt` | `308329…6622` | 0.715 | 0.71 | 0.72 | 0.72 BUY | 0.01 | 0.715 |
| sports | `unl-wal-nor-2026-10-01-nor` | `410463…1103` | 0.495 | 0.49 | 0.5 | 0.5 BUY | 0.01 | 0.495 |
| sports | `unl-grc-nld-2026-10-01-nld` | `110180…5114` | 0.065 | 0.06 | 0.07 | 0.07 BUY | 0.01 | 0.065 |

(`/midpoints` и `/prices` вызваны с интервалом 2 с — расхождение 0,9625 / 0,962 у `bitcoin-above-82k` — разные мгновения книги.)

### 3.3 Что именно за число (проверено на `/books` для 5 токенов)

- `/prices`: `BUY` = **лучший bid** (максимум по bids), `SELL` = **лучший ask** (минимум по asks) — 5 из 5 токенов совпали с книгой (`clob_books_5.json` против `clob_prices_10x2.json`). Доки внутренне противоречивы (§2.5).
- `/midpoints` = `(bid + ask) / 2`. При пустой стороне ask подставляется 1: `bitcoin-above-74k` — bids до 0.999, asks нет (0 уровней), `/prices` SELL = `"1"`, `/midpoints` = `0.9995`. Для пустой стороны bid в Gamma `bestBid` просто отсутствует, а `outcomePrices ["0.0005","0.9995"]` при `bestAsk 0.001` (рынки `will-tarcisio…`, `will-lebron-james…`) согласуется с bid = 0 **[вывод]**; в CLOB эту ситуацию не проверял.
- Книга: `bids` отсортированы по возрастанию цены, `asks` — по убыванию; лучшие цены — **последние** элементы массивов — подтверждено (5 из 5) и совпадает с `market-data/prices-order-books.md`. `timestamp` — миллисекунды, строкой; `hash` меняется при изменении книги.
- Для **neg-risk** токенов поле `last_trade_price` в `/books` не совпало с `/last-trades-prices`: `2026-balance-of-power…` — книга `0.920`, last-trades `0.08`; `will-flvio-bolsonaro…` — книга `0.379`, last-trades `0.621` (то есть `1 − цена`); у `will-geraldo-alckmin…` совпало (`0.001`). Не менялось за 8 минут (`clob_books_5.json`, `clob_books_5_later.json`). Причину не проверял (возможно, цена сделки по токену-дополнению) **[вывод]**. Gamma `lastTradePrice` (0.621) совпал с `/last-trades-prices`.
- У `/midpoints`, `/prices`, `/last-trades-prices` в ответе **нет временной метки**; метка есть только у `/books` (`timestamp`).
- Доки (`concepts/prices-orderbook.md`): «The displayed price is the **midpoint** of the bid-ask spread. If the spread is wider than $0.10, the last traded price is shown instead.» — правило отображения на сайте; в выборке не было открытого рынка со спредом > 0,10, поэтому как `/midpoints` и Gamma ведут себя в этом случае — **UNVERIFIED**.

### 3.4 Свежесть CLOB-числа

- Заголовки CLOB-ответов: `cf-cache-status: DYNAMIC`, `cache-control` нет — CDN не кэширует **[живьём]**.
- Задержка запроса с этой машины: 0,079–0,091 с (`/midpoints`, 5 повторов), 0,085 с (`/books`) **[живьём]**.
- 5 подряд `/midpoints` по 10 токенам, шаг 4 с (19:38:37–19:38:53): у 9 токенов значение не менялось, у `unl-wal-nor-2026-10-01-nor` — `0.495 → 0.495 → 0.495 → 0.5 → 0.495` (`clob_midpoints_poll_1…5.json`). Матчи, по-видимому, были на перерыве (`period: "HT"` в `events_sports_live.json` в 19:42), динамики игры не наблюдалось.
- `timestamp` книги не равен времени ответа: отставание 1,6–6 с (первое чтение 19:37:49) и 5,4–18,4 с (второе чтение 19:45:23) для 5 токенов — метка времени последнего события книги **[вывод]**; `hash` во втором чтении у всех 5 книг другой.
- Доки: push-канал `wss://ws-subscriptions-clob.polymarket.com/ws/market` (события `book`, `price_change`, `last_trade_price`, `best_bid_ask`, `new_market`, `market_resolved`; heartbeat — текстовый кадр `PING` каждые 10 с) (`https://docs.polymarket.com/market-data/realtime-data.md`) **[док]**; к WebSocket я не подключался.

### 3.5 Gamma как источник цены

- Заголовки `markets/keyset`, `events/keyset`, `events/slug`: `cache-control: public, max-age=300`; встречены `cf-cache-status: HIT` с `age: 110 / 131 / 177`. Повтор того же URL через 131 с вернул **побайтно тот же** ответ (sha1 `e9f65d69…` у `markets_sports_moneyline_top.json` и `cached_repeat_sports_moneyline.json`) **[живьём]**. Теги: `max-age=1800`.
- `updatedAt` строки рынка у всех 10 рынков в одном ответе одинаковый — `2026-10-01T19:37:22.488973Z` (ответ в 19:39:17); раньше у `unl-den-prt` и `unl-wal-nor` встречались `19:33:23` и `19:35:23` — шаги ≈ 2 минуты **[вывод: цены строк обновляются пакетом раз в ~2 мин; в доках не заявлено]**.
- Пример расхождения: `unl-wal-nor` — Gamma из кэша (сформирован 19:37:06): `outcomePrices ["0.485","0.515"]`, bid 0.48, ask 0.49; CLOB `/midpoints` в 19:37:42: `0.495`; свежий Gamma в 19:39:17: `0.495`. В 19:39:17 все 10 цен Gamma совпали с CLOB (±0,0005) — рынки за 2 минуты не двигались, то есть Gamma «близка», но не «живая».
- `outcomePrices[0]` = `(bestBid+bestAsk)/2` в 29 из 32 сохранённых рынков (остальные 3 — округление и закрытый рынок).

## 4. История цен

### 4.1 Два эндпойнта

| | CLOB `GET /prices-history` | Data API v2 `GET /v2/prices-history` |
|---|---|---|
| Базовый URL | `https://clob.polymarket.com` | `https://data-api.polymarket.com` |
| Параметры | `market` (= token_id), `interval` ∈ `max, all, 1m, 1w, 1d, 6h, 1h`, `startTs`, `endTs`, `fidelity` (минуты, по умолчанию 1) | `token_id`, ровно одна форма окна: `interval` или `start` (+`end`, ≤ 15 суток) или `as_of`; `bucket_seconds` 60…86400; `limit` ≤ 10 000; `cursor` |
| Ответ | `{"history":[{"t":1790879791,"p":0.525},…]}` | `{"data":[{"timestamp":1790880060,"price":0.525,"resolution_seconds":60},…],"pagination":{"limit":10000,"offset":0,"has_more":false,"next_cursor":null}}` |
| Время | `t` — unix-секунды | `timestamp` — unix-секунды |
| Кэш | `cache-control: public, max-age=90`, `cf-cache-status: HIT`/`MISS` | `cache-control: public, max-age=90` |
| Лимит (IP) | 1000 / 10 с | 200 / 10 с |
| Статус | работает (200) | «replaces the CLOB-hosted route» (changelog 2026-09-04) |
| Batch | `POST /batch-prices-history` `{markets ≤ 20, start_ts, end_ts, interval, fidelity}` → `{"history":{"<token>":[…]}}` — [док], не проверял | — |

Источники: `https://docs.polymarket.com/api-reference/markets/get-prices-history.md`, `…/get-a-tokens-price-history.md`, `…/get-batch-prices-history.md`, `…/api-reference/rate-limits.md`.

### 4.2 Реальные запросы (токен Portugal YES из матча в игре; рынок создан 2026-09-18)

| Запрос | Время | Точек | Шаг | Конец серии |
|---|---|---|---|---|
| CLOB `interval=1h&fidelity=1` (HIT, `age: 197`) | 19:39:40 | 62 | 41–78 с, последняя пара точек с одинаковым `t` | `t` на 203 с старше ответа (`hist_clob_portugal_1h_f1.json`) |
| CLOB то же + `&cb=<случайное>` (обход кэша, MISS) | 19:40:17 | 60 | 45–76 с | ≈ 60 с старше ответа (`hist_clob_portugal_1h_bust.json`) |
| CLOB `interval=1d&fidelity=1` | 19:40:52 | 1441 | 83 % интервалов 55–65 с, максимум 103 с, один дубль `t` | 19:37:16, на 3,6 мин старше ответа (`hist_clob_portugal_1d_f1.json`) |
| CLOB `interval=max` (без `fidelity`) | 19:40:54 | 1928 | ≈ 600 с (598–601) | 19:36:17, от 09-18 10:20:11 (`hist_clob_portugal_max.json`) |
| v2 `interval=1h` (MISS) | 19:40:13 | 56 | 55 бакетов по 60 с (`resolution_seconds:60`, сетка :00 с) 18:41:00–19:35:00 + «терминальный тик» `resolution_seconds:0` в 19:40:11 | бакетов 19:36–19:39 **нет** на момент ответа; тик на 9 с старше (`hist_v2_portugal_1h.json`) |
| v2 `interval=1d` | 19:40:56 | 1436 | 60 с (1434 раза) и один разрыв 329 с | тик 19:40:29, на 27 с старше (`hist_v2_portugal_1d.json`) |
| v2 окно 24 ч, начало −6,96 сут (`start=<…>&end=<start+86400>&bucket_seconds=60`) | 19:40:58 | 1385 | 60 с (1328), 120 с (55), 59 с (1) | данные есть: 1-минутные бакеты на ≈ 7 суток назад (`hist_v2_portugal_6d_ago_b60.json`) |
| v2 окно ≈ −12 сут, `bucket_seconds=60` | 19:41:01 | **0** | — | `{"data":[],"pagination":{…"has_more":false}}` (`hist_v2_portugal_12d_ago_b60.json`) |

### 4.3 Что из этого следует (разрешение и глубина)

- **Самое мелкое реальное разрешение:** 60 с — `bucket_seconds=60` в v2 и `fidelity=1` в CLOB; плюс в v2 точный тик (`resolution_seconds: 0`) в конце серии. Бакеты v2 лежат на сетке целых минут; у CLOB-серии шаг 41–79 с, плавающий.
- **Глубина 60-секундных данных:** доки — «1-minute data at least 7 days, 5-minute at least 60, 30-minute at least 90 … Three-hour and twelve-hour data is permanent» (back to 2022-11-18); явные окна — не более 15 суток; `interval=max` — вся жизнь рынка (`…/get-a-tokens-price-history.md`) **[док]**. Живьём: данные 1 мин есть на 6,96 сут назад, на 12 сут — пусто **[живьём]**.
- **Умолчания:** v2 при пропущенном `bucket_seconds` выбирает ширину сам: окно ≤ 12 ч — 60 с (час = 60 точек); `1d` — 60 с (проверено: 1436 точек), `1w` — 300, `1m` — 1800, `max` — 43 200 (12 ч) **[док]**; для CLOB `interval=max` вернул шаг ≈ 600 с **[живьём]**.
- **Свежесть истории хуже, чем у `/midpoints`:** CLOB-история кэшируется на 90 с (первый запрос показал хвост на 203 с старше); в v2 закрытые минутные бакеты материализуются с отставанием ≈ 5 минут (в 19:40:13 последний бакет — 19:35:00), но серия заканчивается свежим тиком (отставание 9 и 27 с) **[живьём]**.

### 4.4 Что за цена в истории

В серии на рынке с шагом 0,01 **1439 из 1441** (CLOB) и **1435 из 1436** (v2) значений кратны 0,005, но не 0,01 (`0.705`, `0.695`, `0.715`, `0.725`) — это не цены сделок (они на сетке 0,01), а **мидпоинты** книги **[вывод]**. Что именно в терминальном тике v2 (мидпоинт или последняя сделка) — **UNVERIFIED**.

## 5. Жизненный цикл: закрытый / разрешённый рынок

Рынок: `dota2-aur1-liquid-2026-10-01` (id `5140154`, moneyline, `outcomes ["Aurora","Team Liquid"]`, `conditionId 0xcd1408…bf45`), `closedTime 2026-10-01 19:36:36`; запросы 19:41–19:46 (`sample_gamma_market_resolved.json`, `resolved_*.json`).

| Что смотрим | Ответ | Файл |
|---|---|---|
| Gamma, рынок | `closed:true`, **`active:true`**, `acceptingOrders:false`, `umaResolutionStatus:"resolved"`, `automaticallyResolved:true`, `outcomePrices ["1","0"]`, `closedTime "2026-10-01 19:36:36.105447+00"`, `lastTradePrice 0.999`, `bestBid 0.999`, `bestAsk 1` (хвост книги до закрытия), `resolvedBy 0x6507…` | `sample_gamma_market_resolved.json` |
| Gamma, событие | `ended:true`, `live:false`, **`closed:false`** (в событии остались открытые рынки), `score "000-000\|2-1\|Bo3"`, `period "3/3"`, `finishedTimestamp:null` | `resolved_event_dota2.json` |
| CLOB `GET /book?token_id=` | **HTTP 404** `{"error":"No orderbook exists for the requested token id"}` | `resolved_clob_book_yes.json` |
| CLOB `GET /midpoint?token_id=` | **HTTP 404**, то же сообщение | `resolved_clob_midpoint_yes.json` |
| CLOB `GET /last-trade-price?token_id=` | 200 `{"price":"0.999","side":"SELL"}` (не 1.0) | `resolved_clob_lasttrade_yes.json` |
| CLOB batch `/midpoints`, `/prices` с закрытым токеном среди живых | 200; ключ закрытого токена **пропущен**, остальные на месте | `mixed_midpoints_with_closed.json`, `mixed_prices_with_closed.json` |
| CLOB batch `/last-trades-prices` с закрытым токеном | 200; закрытый токен **присутствует** (`0.999 SELL`) | `mixed_last_trades_with_closed.json` |
| v2 `GET /v2/prices-history?token_id=…&interval=6h` | работает после расчёта (356 точек); хвост: `19:36:40 0.9995 (res 0)` и **`19:36:41 1.0 (res 0)`** — точка расчёта (выплата токена YES) | `resolved_v2_hist_6h_yes.json` |
| v2 `GET /v2/resolutions?condition=0xcd14…` | `{"data":[{"status":"resolved","payouts":[1000000,0],"resolution_source":"reported","was_disputed":false,"was_arbitrated":false,"resolved_block":94785501,"resolved_at":"2026-10-01T19:36:32Z",…}]}` | `resolved_v2_resolutions.json` |

- **Лаг закрытия.** 5-минутный рынок `btc-updown-5m-1790883300` (окно 19:35:00–19:40:00) в 19:42:14 был `closed:true`, `umaResolutionStatus:"resolved"`, `outcomePrices ["0","1"]`, `automaticallyResolved:true` (`search_btc_updown.json`) — расчёт не позже 2,5 мин после конца окна. Между концом матча и `closedTime` лаг не измерял.
- **Итог не всегда {0, 1}.** Доки (`concepts/resolution.md`): «Unknown/50-50 … Market resolves 50/50 — each token redeems for $0.50»; у дневных Up/Down: «If the final "Close" price for both of these candles is exactly equal on Binance, this market will resolve 50-50.» (поле `description` рынка `bitcoin-up-or-down-on-october-2-2026`, `search_btc_updown.json`); в v2-истории выплата «0 lost, 1 won, fractional for voided or split resolutions» **[док]**.
- **Сроки по UMA-процессу** (доки): без спора ≈ 2 ч после предложения, со спором 4–6 суток (`concepts/resolution.md`) **[док]**; у рынков с `automaticallyResolved:true` (Dota, 5m-крипто) лаг закрытия я измерил только для 5m-крипто (≤ 2,5 мин), для матча — нет.
- **Листинг закрытых:** `markets/keyset?closed=true&order=closedTime&ascending=false` работает **[живьём]**.

## 6. Лимиты и условия использования

### 6.1 Лимиты (дословно с `https://docs.polymarket.com/api-reference/rate-limits.md`, 2026-10-01) **[док]**

> «The limits on this page are IP-based and enforced using Cloudflare's throttling system. When you exceed the limit for any endpoint, requests are throttled (delayed/queued) rather than immediately rejected. Limits reset on sliding time windows.»

| Семейство | Эндпойнт | Лимит |
|---|---|---|
| General | все | 15 000 req / 10 с |
| Gamma | General / `/events` / `/markets` / листинг `/markets`+`/events` / `/tags` / `/public-search` | 4 000 / 500 / 300 / 900 / 200 / 350 req / 10 с |
| CLOB market data | `/book` / `/books` / `/price` / `/prices` / `/midpoint` / `/midpoints` / `/prices-history` | 1 500 / 500 / 1 500 / 500 / 1 500 / 500 / 1 000 req / 10 с |
| CLOB | General | 9 000 req / 10 с |
| Data API v2 | General / `/v2/prices-history` / `/v2/status` | 800 / 200 / 100 req / 10 с |

- Для `/events/keyset`, `/markets/keyset` и `/last-trades-prices` отдельной строки в таблице нет — какой бакет применяется, **UNVERIFIED**.
- Data API v2: «Rate limiting: `429` with `Retry-After` is the busy signal for heavy queries … Each caller also has a per-client request allowance, and bursts past it get the same `429` with `Retry-After`» (OpenAPI на `…/get-a-tokens-price-history.md`) **[док]**. Ни одного 429 я не получил (62 запроса); единственный заметно медленный ответ — 1,0 с (Gamma `markets/keyset` с `sports_market_types=moneyline`, `markets_sports_moneyline_top.json`), остальные — 0,07–0,27 с.
- Ключ/авторизация: «Public market data is available without credentials.» (`https://docs.polymarket.com/getting-started/api.md`); у Data API v2: «**Auth**: none. All data routes are public; no API key or token is required.» (OpenAPI на странице `get-a-tokens-price-history`) **[док]** и **[живьём]** (все 62 запроса без заголовков авторизации).
- Лимиты на подписи ордеров (`trading-rate-limits`) к чтению не относятся — не читал.

### 6.2 Что доки говорят о праве использовать данные

- Прямого заявления о лицензии на данные, атрибуции, коммерческом/некоммерческом использовании или перераспространении в прочитанных страницах доков **нет** (поиск по 34 страницам: `terms of use/service`, `licen[cs]e`, `attribution`, `commercial`, `redistribut`, `permitted`, `polymarket.com/tos` — совпадений по условиям данных нет: `license: MIT` — строка OpenAPI-файла спецификации, «licensed» — про референсные цены PolyBolt (ниже), `attribution` — атрибуция ордеров в Builder-программе, `permitted` — гео-ограничения Perps).
- Спорт: «Sports data is provided for informational purposes only. It may be delayed, contain errors, or omit recent events. Polymarket does not provide trading or investment advice, and this data should not be used as the basis for a trading decision.» (`market-data/realtime-data.md`, раздел Sports Stream) **[док]**.
- Геоограничения — только на размещение ордеров (§1) **[док]**.
- Референсные цены (крипто/TWAP) — канал с авторизацией: «Reference price subscriptions require authentication.» (`market-data/realtime-data.md`); changelog 2026-09-15: «streams licensed crypto, equity and Chainlink TWAP reference prices (`price.crypto`, `price.equity`, `price.crypto.twap`, CLOB API credentials required)» — без ключа CLOB их не получить **[док]**.
- Из существующего research проекта: «ICE also became exclusive global distributor of Polymarket's event data to institutions» (`docs/research/prediction_market_research.md:55`, ссылка там на Yahoo Finance/ICE; здесь не перепроверялось) — условия институционального распространения данных мне неизвестны.
- Страницу Terms of Use (`https://polymarket.com/tos`) не открывал — вне разрешённых хостов этого среза; её содержание **UNVERIFIED**.

## 7. Крипто «Up or Down» (для справки)

Найдено поиском `GET /public-search?q=Bitcoin%20Up%20or%20Down&limit_per_type=5&events_status=active` (19:42) и прямыми `GET /events/slug/<slug>` (`search_btc_updown.json`, `updown_{eth,sol,xrp}_15m.json`) **[живьём]**.

| Серия (`seriesSlug`) | Окно | Шаблон slug события | Пример (BTC) | Источник резолва (`resolutionSource`) | tick |
|---|---|---|---|---|---|
| `btc-up-or-down-5m` | 5 мин | `btc-updown-5m-<unix начала>` | `btc-updown-5m-1790883300` (19:35:00–19:40:00Z) | `https://data.chain.link/streams/btc-usd-twap-60s-streams` | 0.001 |
| `btc-up-or-down-15m` | 15 мин | `btc-updown-15m-<unix начала>` | `btc-updown-15m-1790883000` (19:30–19:45Z) | то же | 0.01 |
| `btc-up-or-down-4h` | 4 ч | `btc-updown-4h-<unix начала>` | `btc-updown-4h-1790870400` (16:00–20:00Z) | то же | 0.001 |
| `btc-up-or-down-hourly` | 1 ч | `bitcoin-up-or-down-<месяц>-<день>-<год>-<час><am/pm>-et` | `bitcoin-up-or-down-october-1-2026-3pm-et` (19:00–20:00Z) | `https://www.binance.com/en/trade/BTC_USDT` | 0.01 |
| `btc-up-or-down-daily` | сутки | `bitcoin-up-or-down-on-<месяц>-<день>-<год>` | `bitcoin-up-or-down-on-october-2-2026` (с 2026-10-01T16:00Z до 2026-10-02T16:00Z) | `https://www.binance.com/en/trade/BTC_USDT` | 0.01 |

- Для ETH/SOL/XRP шаблон проверен на 15m: `eth-updown-15m-1790883900`, `sol-updown-15m-1790883900`, `xrp-updown-15m-1790883900` (все 200), серии `eth-up-or-down-15m` и т. д., источник `https://data.chain.link/streams/{eth,sol,xrp}-usd-twap-60s-streams` (`updown_*_15m.json`). Остальные интервалы для ETH/SOL/XRP — **UNVERIFIED**.
- Исходы `["Up","Down"]`, индекс 0 = Up; один рынок на событие (`markets` длиной 1); `feesEnabled:true`; `negRisk:false`. `eventMetadata.priceToBeat` присутствует не везде (5m: `84703.39960875176`, 4h: `84080.04566451331`, daily: `84162.65`, hourly: `84802.02`; у 15m-события `eventMetadata:null`).
- Правило резолва, 5m/15m/4h (`description`): «This market will resolve to "Up" if the time-weighted average price (TWAP) of Bitcoin, generated by Chainlink, of the time range specified in the title is greater than or equal to the price at the beginning of that range. Otherwise, it will resolve to "Down".» Hourly: «…"Up" if the close price is greater than or equal to the open price for the BTC/USDT 1 hour candle…». Daily: сравнение «Close» 1-минутной свечи Binance BTC/USDT в 12:00 ET двух соседних суток.
- История правил (changelog `https://docs.polymarket.com/changelog/predictions.md`) **[док]**: 2026-08-07 — резолв по Chainlink TWAP (5m: окно 30 с; 15m и 4h: 60 с); 2026-08-14 — для 5m окно 60 с; задержка тейкера на крипто-рынках: 250 мс → 50 мс (2026-08-17) → **150 мс** (2026-09-04, 14:00 UTC).
- Рынки создаются заранее: у `btc-updown-5m-1790883300` `startDate` рынка `2026-09-30T19:43:30Z` (за ~24 ч до окна 19:35 следующих суток) — торговля идёт до начала окна измерения; начало окна — `eventStartTime`.
- `volume24hr` событий на 19:42 UTC: daily 22 840, hourly 21 422, 4h 12 793, 15m 4 352, 5m 2 977 (единица в доках не названа) (`search_btc_updown.json`). Для сравнения, в проекте стоит «BTC Up/Down ~$52.5K/24ч (внутренняя оценка, не пере-верифицирована)» (`docs/research/c2_deep_review.md:73`).
- Перечислить серии 5m/15m/4h через `/series?recurrence=` нельзя (допустимо только `daily, weekly, monthly, annual, hourly`, §2.5); работают поиск и шаблон slug.

## 8. Спорт

- **Теги:** игры лежат под `sports` (id 1) и `games` (100639), вид спорта (`soccer` 100350, …), лига (`uefa-nations-league` 100816); в `/sports` — 473 записи вида `{sport:"ufl", tags:"1,100639,1186,105925", primaryTagId, series, …}` (`sports_list.json`). Под тегом 1 в топе по объёму встретились футбол (UNL), киберспорт (CS2, Dota 2), рынки с префиксами `itf-` и `setka…` (теннис/Setka), NFL, MLB **[живьём]**.
- **Живая игра (событие):** `live`, `ended`, `score` (`"1-2"`; киберспорт `"000-000|0-0|Bo3"`), `period` (`"1H"`, `"HT"`, `"1/3"`), `elapsed` (`"45"`, `""` в перерыве, `null` у киберспорта), `startTime` (`"2026-10-01T18:45:00Z"`), `eventDate`, `eventWeek`, `gameId`, `teams[]` (`id,name,league,abbreviation,ordering "home"/"away",record,logo,color`), `sport{}` (`id,sport,name,resolution,tags,primaryTagId,series`), `seriesSlug`, `series[]`, `eventMetadata` (идентификаторы Optic Odds) (`sample_gamma_event_sports_live.json`; в 19:37 `period:"1H"`, `elapsed:"45"`; в 19:42 — `period:"HT"`).
- **Рынок игры:** `gameStartTime` (`"2026-10-01 18:45:00+00"`, не-ISO), `sportsMarketType` (`moneyline`, `totals`, … — 286 значений в `/sports/market-types`), `line` (`3.5` у тоталов), `gameId` (у части рынков), `secondsDelay` (**1** у живого футбольного рынка), `clearBookOnStart:true`, `feesEnabled:true`, `feeType:"sports_fees_v3"`, `feeSchedule{exponent 1, rate 0.05, takerOnly true, rebateRate 0.15}`, `oneHourPriceChange`, `oneDayPriceChange`, `marketMetadata` (Optic Odds) (`sample_gamma_event_sports_live.json`, рынок `unl-den-prt-2026-10-01-prt`).
- **Шаблон slug:** `<лига>-<команда1>-<команда2>-<YYYY-MM-DD>[-<исход>]`: `unl-den-prt-2026-10-01-prt`, `cs2-faze-nemi1-2026-10-01`, `nfl-pit-cle-2026-10-02`, `mlb-phi-atl-2026-10-01`; доп. события игры: суффиксы `-more-markets`, `-halftime-result`, `-player-props`, … (доки; живьём `unl-ger-ser-2026-10-01-more-markets` — 60 рынков).
- **Фильтр живых игр:** `events/keyset?tag_id=1&live=true&closed=false` вернул 6 идущих игр (19:42, у 5 из них `period:"HT"`) **[живьём]**.
- **Начало игры.** Доки (`concepts/markets-events.md`): «outstanding limit orders are **automatically cancelled** once the game begins, clearing the order book at the official start time. However, game start times can shift — if a game starts earlier than scheduled, orders may not be cleared in time.» — в данных `clearBookOnStart:true`. Как выглядит `/midpoints` в момент очистки книги, не наблюдал (все игры в выборке уже шли) **UNVERIFIED**.
- **Push-канал по счёту:** `wss://sports-api.polymarket.com/ws` (кадры: `gameId, leagueAbbreviation, homeTeam, awayTeam, status, live, ended, score, period, elapsed…`), «Sports data … may be delayed» (§6.2) — к каналу не подключался **[док]**.
- Волатильность шансов внутри игры не оценивалась (по условиям среза); из полей по ней есть `oneHourPriceChange`/`oneDayPriceChange` у рынка и минутная история (§4).

## 9. Что не проверено

- WebSocket-каналы (`ws-subscriptions-clob…/ws/market`, `sports-api…/ws`, RTDS, PolyBolt) — к ним не подключался (хосты вне трёх разрешённых); всё о них — из доков.
- `https://polymarket.com/api/geoblock` не вызывал; `https://polymarket.com/tos` не читал; лицензия/условия на данные — не установлены.
- Поведение при превышении лимитов: ни 429, ни замедления не видел; предел 500 токенов на batch проверял только до 20 элементов; `limit > 100` на keyset не пробовал.
- Верхние категории приложения кроме трёх (finance, tech, culture, geopolitics…) и их id — не перечислены; эндпойнта «меню категорий» не нашёл.
- Поведение `/midpoints` и Gamma при спреде > 0,10 (правило сайта «показать последнюю сделку») — рынка с таким спредом в выборке не было.
- Момент начала игры и очистка книги — не наблюдал; динамику цены в игре (матчи были в перерыве) — не измерял.
- Устойчивость 2-минутного шага `updatedAt` Gamma (3 наблюдения) и 5-минутного лага минутных бакетов v2 (3 наблюдения, один рынок) — не проверялась на других рынках и в другое время.
- Всё измерено в один будний вечер 19:32–19:47 UTC; ночью, в выходные, в пики спортивных событий не смотрел.
- Что именно в терминальном тике v2 (мидпоинт или сделка) и причина расхождения `last_trade_price` в `/books` у neg-risk токенов — не выяснял.
- Глубина стакана и проскальзывание, реальная ликвидность рынков под ставки — не анализировались.
- Крипто Up/Down для ETH/SOL/XRP, кроме 15m, и для BTC — состав окон на других часах; повторяемость `priceToBeat` — не проверялись.
- Не читал: `trading-rate-limits`, страницы Perps, Bridge, Combos, builder-программы (кроме обзорных).

## 10. Что это значит для Herd (только факты: какое число, откуда, с какой частотой)

| Число | Откуда | Частота обновления на стороне Polymarket (наблюдалась) | Примечания |
|---|---|---|---|
| Текущая вероятность исхода (мидпоинт токена) | CLOB `POST /midpoints`, до 500 токенов за запрос | непрерывно; не кэшируется CDN; ответ 0,08–0,09 с; у 1 из 10 токенов значение менялось за 16 с наблюдения (матчи в перерыве) | `(bid+ask)/2`; шаг цены рынка 0.01 → значения кратны 0.005; 0.001 → кратны 0.0005; одна пустая сторона даёт 0.0005 / 0.9995 |
| Лучшие bid/ask | `POST /prices` (BUY = bid, SELL = ask) или `POST /books` | то же; у `/books` есть `timestamp` (мс), отставание 1,6–18 с | в книгах и ответах — строки |
| Последняя сделка | `POST /last-trades-prices` | то же; без временной метки; для закрытого токена продолжает отвечать | `book.last_trade_price` у neg-risk токенов расходился с ним |
| Каталог (id, slug, вопрос, токены, tick, `endDate`, `gameStartTime`, теги) | Gamma `/markets/keyset` | CDN-кэш 300 с; цены в строках обновляются пакетами (≈ 2 мин) | цены Gamma для расчёта раундов — устаревшие по меркам секунд |
| Минутные бакеты и тик | v2 `GET /v2/prices-history`, окно ≤ 15 суток | бакет 60 с; закрытые бакеты отстают ≈ 5 мин; терминальный тик отстаёт 9–27 с | 60-секундные данные — минимум ≈ 7 суток |
| Итог рынка | Gamma `closed`, `outcomePrices`; `GET /v2/resolutions?condition=` (`payouts`); последняя точка v2-истории | 5m-рынок закрыт ≤ 2,5 мин после окна; для матчей лаг не измерен | возможен расчёт 50/50 и дробные выплаты (по докам) |
| Закрытие рынка как сигнал | `/book`, `/midpoint` → 404; ключ токена пропадает из `/midpoints` и `/prices`; Gamma `closed:true` при `active:true` | сразу после расчёта | один закрытый токен в batch не ломает запрос (200) |

Другие факты, влияющие на форму данных:
- Цена токена ограничена (0, 1): наблюдались bid/ask от 0.001 до 0.999 и мидпоинты 0.0005…0.9995; шаги цены в доках — 0.1, 0.01, 0.005, 0.0025, 0.001, 0.0001; `0.0025` — только для отдельных рынков World Cup (`market-data/market-details.md`).
- У «самых объёмных» рынков доля вырожденных цен велика (§2.4: 4 и 7 из 10 в политике, 2 из 10 в крипто и в спорте).
- Лимиты привязаны к **IP**: один сервер Herd с одним исходящим IP делит один бюджет (например, `/midpoints` — 500 запросов / 10 с, то есть 50 в секунду); текущий сэмплер цены Hyperliquid в проекте опрашивает раз в 2 с (`docs/reviews/2026-10-01-goals-vs-state.md:134`).
- 5m/15m/4h-рынки Polymarket имеют фиксированные окна и собственные токены; новое окно = новый рынок с новой парой токенов (slug по unix-времени начала).
- В начале игры по докам стакан очищается; на живом рынке `secondsDelay = 1`.

## 11. Воспроизведение (точные команды)

```bash
# каталог: топ-10 рынков политики по объёму за сутки (теги: sports=1, politics=2, crypto=21)
curl 'https://gamma-api.polymarket.com/markets/keyset?tag_id=2&closed=false&order=volume24hr&ascending=false&limit=10'
# следующая страница
curl "https://gamma-api.polymarket.com/markets/keyset?tag_id=2&closed=false&order=volume24hr&ascending=false&limit=10&after_cursor=$NEXT_CURSOR"
# живые игры
curl 'https://gamma-api.polymarket.com/events/keyset?tag_id=1&live=true&closed=false&order=volume24hr&ascending=false&limit=6'
# токены рынка: JSON.parse(market.clobTokenIds) — индекс совпадает с JSON.parse(market.outcomes)

# живая цена, много токенов за один запрос
curl -X POST 'https://clob.polymarket.com/midpoints' -H 'Content-Type: application/json' \
  --data '[{"token_id":"<token1>"},{"token_id":"<token2>"}]'
curl -X POST 'https://clob.polymarket.com/prices' -H 'Content-Type: application/json' \
  --data '[{"token_id":"<token1>","side":"BUY"},{"token_id":"<token1>","side":"SELL"}]'
curl -X POST 'https://clob.polymarket.com/books' -H 'Content-Type: application/json' \
  --data '[{"token_id":"<token1>"}]'
curl -X POST 'https://clob.polymarket.com/last-trades-prices' -H 'Content-Type: application/json' \
  --data '[{"token_id":"<token1>"}]'

# история
curl "https://data-api.polymarket.com/v2/prices-history?token_id=<token>&interval=1h"
curl "https://data-api.polymarket.com/v2/prices-history?token_id=<token>&start=<unix>&end=<unix>&bucket_seconds=60"
curl "https://clob.polymarket.com/prices-history?market=<token>&interval=1d&fidelity=1"

# итог рынка
curl "https://data-api.polymarket.com/v2/resolutions?condition=<conditionId>"
```

## 12. Образцы и логи

Каталог: `<scratchpad агента>/polymarket/` (все ответы — как вернул сервер; `*.headers` — заголовки; `requests.log` — журнал; `docs/` — страницы доков).

| Файл | Что |
|---|---|
| `sample_gamma_market_binary_politics.json` | рынок Yes/No (элемент из листинга) |
| `sample_gamma_event_sports_live.json` | событие идущего матча: `live`, `score`, `period`, `teams`, `sport`, 3 рынка moneyline |
| `sample_gamma_event_crypto_updown_15m.json` | ETH Up/Down 15m: серия, `resolutionSource`, исходы Up/Down |
| `sample_gamma_market_resolved.json` | рынок, закрытый за 5 минут до запроса |
| `markets_{politics_top_try1,politics_top_page2,crypto_top,sports_top,sports_moneyline_top}.json` | листинги топ-10 |
| `events_politics_top.json`, `events_sports_live.json` | листинги событий (1,3 МБ и 432 КБ) |
| `clob_midpoints_10.json`, `clob_prices_10x2.json`, `clob_last_trades_10.json`, `clob_books_5.json` (+`_later`), `clob_midpoints_poll_1…5.json` | живая цена |
| `hist_clob_portugal_*.json`, `hist_v2_portugal_*.json`, `resolved_v2_hist_6h_yes.json` | история |
| `resolved_*.json`, `mixed_*_with_closed.json` | жизненный цикл |
| `search_btc_updown.json`, `updown_{eth,sol,xrp}_15m.json` | крипто Up/Down |
| `sports_list.json`, `sports_market_types.json`, `tag_*.json`, `tags_carousel.json` | справочники |
| `requests.log`, `SAMPLES.txt`, `tokens10.tsv` | журнал, индекс, набор токенов |

Источники (доки, 2026-10-01): `https://docs.polymarket.com/` — `market-data/discover-markets`, `market-data/market-details`, `market-data/prices-order-books`, `market-data/realtime-data`, `concepts/markets-events`, `concepts/prices-orderbook`, `concepts/resolution`, `getting-started/api`, `api-reference/rate-limits`, `api-reference/geoblock`, `api-reference/markets/{get-prices-history,get-a-tokens-price-history,get-batch-prices-history,get-resolution-state,list-markets-keyset-pagination}`, `api-reference/events/list-events-keyset-pagination`, `api-reference/tags/list-tags`, `changelog/predictions` (всё с суффиксом `.md`).
