# Поведение ботов «как у людей»: открытые стратегии, исследования о частных трейдерах, копирование через API

Дата чтения всех источников: **2026-10-16** (живые запросы около 08:59–09:15 UTC). Срез исследовательский: код не менялся, регистраций нет, авторизованных эндпойнтов не вызывали. Только публичные read-only запросы `curl`.

Пометки: **[живьём]** — получено запросом/страницей 2026-10-16; **[вывод]** — моя интерпретация; **UNVERIFIED** — не проверено. Звёзды — как показал `api.github.com/repos/<repo>` 2026-10-16.

Контекст: сегодня у ботов 5 стратегий (`BotStrategy.java`: Maximalist, Contrarian, Trend Follower по большинству прошлого раунда, Wildcard, Cautious), цен они не видят. Лицензия проекта Apache-2.0.

## 0. Коротко

1. Открытых готовых правил много, все сводятся к нескольким индикаторам (EMA/SMA-кросс, RSI, Bollinger, MACD, пробой канала Donchian). Переписать правило с нуля — можно; копировать код GPL (freqtrade, backtrader, OctoBot) и AGPL (backtesting.py) нельзя.
2. Копирование реальных трейдеров: у Binance **нет** официального API чтения чужих позиций (см. C). У **OKX** и **Hyperliquid** чужие открытые позиции читаются публичными эндпойнтами без ключа, **проверено живьём**. У Polymarket тоже (positions/trades/leaderboard).
3. Лучшее соотношение пользы и усилий — не копирование, а ценовые правила на минутных свечах, которые сервер уже хранит; копирование — вторым слоем, как «сигнал настроения» (агрегат), а не как «бот = конкретный человек».

## A. Открытый код стратегий

| Репозиторий | Лицензия | Звёзды | Что внутри (индикаторы, таймфрейм) | На 1-мин свечах для вызова 3-мин UP/DOWN |
|---|---|---|---|---|
| https://github.com/freqtrade/freqtrade-strategies | GPL-3.0 | 5 556 | Strategy001 (EMA20/EMA50 + Heikin-Ashi, 5m), Strategy002 (RSI<30, Stochastic, Bollinger, SAR, Fisher-RSI, 5m), Supertrend, MultiMa, Bandtastic, UniversalMACD и др. | Идея да (индикаторы на любом таймфрейме); код копировать нельзя (GPL). Исходные параметры под 5m и выходы ROI/stoploss — под раунд не подходят [вывод] |
| https://github.com/freqtrade/freqtrade | GPL-3.0 | 55 450 | `sample_strategy.py`: RSI(buy<30, sell>70), TEMA, Bollinger, `timeframe = "5m"` | То же: правило RSI 30/70 переносимо, код — нет |
| https://github.com/jesse-ai/example-strategies | MIT | 179 | RSI2 (Connors: RSI(2)≤10 при цене выше SMA200 — вход в лонг), Donchian, SMACrossover, MACD_EMA, SimpleBollinger, TurtleRules, DUAL_THRUST | Да для RSI2/SMA/Bollinger/Donchian (параметры периодов); MIT — можно брать идеи и фрагменты с указанием |
| https://github.com/jesse-ai/jesse | MIT | 8 652 | Фреймворк, индикаторы | Фреймворк не нужен, нужны формулы |
| https://github.com/hummingbot/hummingbot | Apache-2.0 | 20 474 | `controllers/directional_trading/`: `bollinger_v1.py`, `bollinger_v2.py`, `macd_bb_v1.py`, `supertrend_v1.py`, `dman_v3.py` | Да; лицензия совместима с нашей. Код привязан к их исполнителям/стакану — переносить логику сигнала, не обвязку [вывод] |
| https://github.com/je-suis-tm/quant-trading | Apache-2.0 | 11 017 | MACD, Awesome Oscillator, Bollinger pattern, Parabolic SAR, Heikin-Ashi, Dual Thrust, London Breakout, Pair trading, Shooting Star (в README) | Да для MACD/Bollinger/SAR; London Breakout, pair trading — нет (сессии/пара активов) |
| https://github.com/mementum/backtrader | GPL-3.0 | 23 489 | Примеры индикаторов/стратегий, библиотека не обновлялась с 2024-08 (pushed_at) | Идеи; код — нет (GPL) |
| https://github.com/polakowo/vectorbt | NOASSERTION (в GitHub-метаданных), см. файл лицензии в репозитории | 9 394 | Векторные бэктесты MA-кросс, RSI и т.д. | Лицензию не разбирал — **UNVERIFIED**; брать только идеи |
| https://github.com/kernc/backtesting.py | AGPL-3.0 | 9 054 | Примеры SMA-кросс, RSI | AGPL — копировать нельзя |
| https://github.com/ta-lib/ta-lib-python | BSD-2-Clause | 12 296 | Формулы индикаторов (RSI, EMA, MACD, BBANDS, STOCH) | Справочник формул; зависимость тянуть не надо — RSI/EMA пишутся в 10 строк |
| https://github.com/Drakkar-Software/OctoBot | GPL-3.0 | 6 793 | Готовые наборы стратегий | Код — нет |

Источники: `api.github.com` (лицензии/звёзды), `raw.githubusercontent.com` файлы Strategy001/002, `sample_strategy.py`, `RSI2/__init__.py`, листинги `controllers/directional_trading` и `user_data/strategies` — все **[живьём]** 2026-10-16.

**Оговорка по применимости (вывод):** все эти стратегии написаны для держания позиции минуты–часы и выхода по ROI/стопу. Раунд у нас — «close против open» за 3 или 15 минут, поэтому от правила нужен только знак сигнала (вверх/вниз) на момент ставки. Что такие правила дают положительное ожидание на 3-минутном горизонте, **не заявляется**: для «похоже на человека» это и не нужно, достаточно, чтобы ботов тянуло к тем же эвристикам, что и людей.

### Рекомендуемый набор из 6 правил (вывод)

Выбор по простоте, документированности и тому, что именно эти правила частные трейдеры реально применяют.

| # | Бот-личность | Правило (на 1-мин свечах) | Источник правила | Поведенческая основа (раздел B) |
|---|---|---|---|---|
| 1 | Momentum / «погоня за ростом» | Знак доходности за последние N свечей (напр. 5–15 мин) → ставка по направлению | Идея Supertrend/EMA-кросса: Strategy001, `supertrend_v1.py` | Погоня за недавним движением, внимание (B3) |
| 2 | Mean reversion / RSI | RSI(14) или RSI(2) <30 → UP, >70 → DOWN (Strategy002, `sample_strategy.py`, jesse RSI2) | freqtrade, jesse (MIT) | Покупка после падения (B4) |
| 3 | Bollinger-отскок | Цена ниже нижней полосы → UP, выше верхней → DOWN | `bollinger_v1/v2.py` (hummingbot, Apache-2.0), quant-trading | Контр-трендовые «дипы» |
| 4 | Breakout (Donchian) | Закрытие выше максимума последних N свечей → UP, ниже минимума → DOWN | jesse `Donchian`, `TurtleRules` (MIT) | Пробои у круглых уровней (B6) |
| 5 | MA cross | EMA(9) × EMA(21) на 1-мин свечах | Strategy001, jesse `SMACrossover` | Самая известная «школьная» стратегия |
| 6 | MACD + реакция на новость | MACD-гистограмма; усиливает ставку при необычном объёме/движении за последнюю минуту | `macd_bb_v1.py`, quant-trading | Внимание к «горячим» активам (B3) |

Существующие Contrarian / Trend Follower / Wildcard / Cautious / Maximalist можно оставить как слой «настроения толпы» и плеча, а новые правила дать как источник направления (поведение плеча и ставки — из B).

## B. Как ведут себя частные трейдеры (исследования)

| # | Источник | Находка в одну строку | Для бота |
|---|---|---|---|
| B1 | Barber & Odean, «Trading Is Hazardous to Your Wealth», J. Finance 2000 — https://ideas.repec.org/a/bla/jfinan/v55y2000i2p773-806.html | 66 465 домохозяйств: те, кто торгует чаще всех, получают 11,4% годовых против 17,9% рынка; средний оборот 75% портфеля в год (**[живьём]**, по сниппету поиска) | Овертрейдинг: часть ботов ставит почти в каждом раунде, часть — редко |
| B2 | Odean, «Are Investors Reluctant to Realize Their Losses?», J. Finance 1998 — https://faculty.haas.berkeley.edu/odean/papers/disposition/disposition.html | Disposition effect: растущую позицию продают примерно на 60% чаще падающей (PGR 14,8% против PLR 9,8%) | Фиксировать выигрыш быстро; после проигрыша «держать» (повторять ту же сторону) |
| B3 | Barber & Odean, «All That Glitters», Rev. Fin. Studies 2008 — https://ideas.repec.org/a/oup/rfinst/v21y2008i2p785-818.html | Частные инвесторы — чистые покупатели «заметных» активов (новости, всплеск объёма, экстремальная доходность дня) | Больше ставок на активы с резким движением/необычным объёмом |
| B4 | Kaniel, Saar & Titman, J. Finance 2008 — https://ideas.repec.org/a/bla/jfinan/v63y2008i1p273-310.html | Частные инвесторы покупают после падения прошлого месяца и продают после роста (контр-трендово на горизонте месяца) | Нужны и контр-трендовые боты (RSI/Bollinger), не только momentum. Горизонт месяца ≠ 3 минуты — **вывод**, а не измерение |
| B5 | Thaler & Johnson, Management Science 1990 — https://ideas.repec.org/a/inm/ormnsc/v36y1990i6p643-660.html | После выигрыша риск растёт («деньги казино»); после проигрыша привлекательны ставки, дающие шанс «отыграться» | Плечо/ставка зависят от последнего результата бота: после выигрыша выше, после проигрыша — либо сброс, либо «отыгрыш» |
| B6 | Coval & Shumway, «Do Behavioral Biases Affect Prices?», J. Finance 2005 — https://ideas.repec.org/a/bla/jfinan/v60y2005i1p1-34.html | Трейдеры CBOT (проп-трейдеры) после утренних убытков берут повышенный риск днём, чтобы отыграться | То же, loss-chasing |
| B7 | Bhattacharya, Holden & Jacobsen, «Penny Wise, Dollar Foolish: Buy-Sell Imbalances On and Around Round Numbers», Management Science 2012 — https://host.kelley.iu.edu/cholden/Bhattacharya%20Holden%20and%20Jacobsen%20(2012).pdf | Частные ордера смещены у круглых цен (избыточные покупки на цент ниже круглого, продажи на цент выше) | Это про цены, а не размер ставки. По **круглым суммам ставок** источник не найден — **UNVERIFIED**; суммы 10/50/100/500 — наше допущение [вывод] |
| B8 | Chague, De-Losso & Giovannetti, «Day Trading for a Living?» (бразильские фьючерсы 2013–2015) — https://papers.ssrn.com/sol3/papers.cfm?abstract_id=3423101 | Из тех, кто торговал дольше 300 дней, 97% в минусе; 1,1% заработал больше мин. зарплаты | Распределение результатов ботов: большинство в минусе, не равный «все ровно» |
| B9 | BIS Bulletin 69, «Crypto shocks and retail losses», февраль 2023 — https://www.bis.org/publ/bisbull69.pdf | Данные по приложениям бирж, 95 стран: «киль» (мелкие) покупал, когда «киты» (≥1000 BTC) продавали; медианный розничный инвестор потерял около половины вложенного | Стадность и поздний вход |
| B10 | ESMA, меры по CFD 2018 — https://www.esma.europa.eu/node/84933 | У 74–89% розничных CFD-счетов убыток; ограничения плеча как мера защиты | Высокое плечо — типичное поведение, а не аномалия |

Цитаты и цифры выше взяты из выдачи поиска и аннотаций 2026-10-16; полные тексты PDF (кроме открытия страниц) я целиком не читал. Количественные данные по **распределению плеч на криптобиржах** и по **кругляшам размеров ставок** в открытых источниках не найдены — **UNVERIFIED**.

## C. Копирование реальных трейдеров через API

### C1. Binance

| Вопрос | Факт | Источник |
|---|---|---|
| Официальный API копи-трейдинга | Только эндпойнты **для самого ведущего трейдера**: `GET /sapi/v1/copyTrading/futures/userStatus` (статус лид-трейдера) и `GET /sapi/v1/copyTrading/futures/leadSymbol` (белый список символов). Оба требуют API-ключа и подписи. Добавлены 2024-06-06. | https://developers.binance.com/docs/copy_trading/future-copy-trading ; https://developers.binance.com/docs/copy_trading/future-copy-trading/Get-Futures-Lead-Trader-Status **[живьём]** |
| Чтение позиций/сделок чужого лид-трейдера официальным API | **Нет**: в разделе Copy Trading его нет (только два эндпойнта выше). | там же **[живьём]** |
| Лидерборд фьючерсов | Раньше «Futures Leaderboard», теперь на сайте «Smart Money». Официального API нет; данные берут сторонние сервисы: https://github.com/Nunnito/Binance-Futures-Leaderboard-API (11 звёзд; описывается как коммерческий API Binance Smart Money, у Binance брать данные не документировано), Apify-акторы (https://apify.com/josrade/binance-smart-money). Мой пробный POST на предполагаемый внутренний путь `/bapi/futures/v3/public/future/leaderboard/getLeaderboardRank` вернул **404** — рабочий внутренний эндпойнт я не нашёл (путь мог быть неверным или закрыт). | поиск и страница репозитория **[живьём]**; статус внутреннего эндпойнта — **UNVERIFIED** |
| Условия Binance про скрейпинг/недокументированные эндпойнты | Текст Terms of Use Binance.com получить не удалось (страница `binance.com/en/terms` отдала только футер). Нашёл формулировку Binance.US: «You must not: … (2) use any robot, spider, other automatic device, or manual process to monitor or copy our Website without our prior written permission» (через поиск; источник: conductatlas.com, вторичный). Для Binance.com — **UNVERIFIED**. | https://conductatlas.com/platform/binanceus/binanceus-terms-of-use/provision/CA-P-021347/prohibition-on-anonymizing-proxies-and-automated-scraping/ |

**Итог по Binance (вывод):** официального способа читать чужие позиции нет; единственный путь — недокументированные веб-эндпойнты или платные сторонние сервисы. Условия Binance.US прямо запрещают автоматический мониторинг сайта без письменного разрешения; для Binance.com текст не прочитан. Для проекта (публичный open-source, Apache-2.0) опираться на это не стоит.

### C2. Bybit и OKX

| Биржа | Что нашёл | Статус |
|---|---|---|
| Bybit | Раздел https://bybit-exchange.github.io/docs/v5/copytrade описывает только размещение ордеров мастер-трейдером (`POST /v5/order/create`, USDT-перпеты); эндпойнта чтения чужих позиций не видно **[живьём]**. Мой пробный запрос `GET /v5/copytrading/public/leader-list` вернул **404** (путь выдуман мной — не доказательство отсутствия). | Публичного чтения чужих позиций у Bybit **не найдено**; отсутствие не доказано — **UNVERIFIED** |
| OKX | Публичные эндпойнты, ключ не нужен. **Проверено живьём:** `GET https://www.okx.com/api/v5/copytrading/public-lead-traders?instType=SWAP&limit=1` → `code:"0"`, список с `uniqueCode`, `pnl`, `pnlRatio`, `aum`, `copyTraderNum`; `GET https://www.okx.com/api/v5/copytrading/public-current-subpositions?instType=SWAP&uniqueCode=<код>&limit=2` → открытые позиции лид-трейдера: `instId` (XRP-USDT-SWAP, MSTR-USDT-SWAP), `posSide`, `lever` (15), `openAvgPx`, `openTime`, `upl`. Страница документации перечисляет также `public-lead-traders-rank`, `-weekly-pnl`, `-daily-pnl`, `-stats`, `-positions`, `-positions-history`; мой запрос на `public-lead-traders-positions` дал **404**, т.е. имена в доках и рабочие пути расходятся — для работающих путей полагаться на проверенные два. | Доки: https://www.okx.com/docs-v5/en/#order-book-trading-copy-trading-rest-api-get-lead-trader-ranks (читал только первые 100 000 из 406 161 символа) |

Лимиты OKX и условия использования публичных copy-trading эндпойнтов: **UNVERIFIED** (в прочитанной части не указаны). Неочевидный момент: ответ идёт по реальным людям (псевдонимы `nickName`), активы — не наши (XRP, MSTR и т.д.) [вывод].

### C3. Hyperliquid

| Вопрос | Факт | Источник |
|---|---|---|
| Читаются ли позиции любого адреса | Да. `POST https://api.hyperliquid.xyz/info` с `{"type":"clearinghouseState","user":"<адрес>"}` вернул состояние чужого адреса без авторизации. Адрес взят из публичного лидерборда (ниже): `0x85ec…2052` — `assetPositions: []` (на момент запроса позиций не было, баланс 27 110,93 USDC, `time` 1792141170367). Документация явно не говорит «любой адрес», но предупреждает, что нужен адрес мастер/субаккаунта, а не агент-кошелька. | **[живьём]** 2026-10-16; https://hyperliquid.gitbook.io/hyperliquid-docs/for-developers/api/info-endpoint |
| Сделки (fills) | `{"type":"userFills","user":"<адрес>"}` → 2000 записей (последняя пачка), поля `coin`, `px`, `sz`, `side`, `time`, `dir` ("Close Long"), `closedPnl`. Доки: максимум 2000 за ответ, доступно 10 000 последних для time-запросов. | **[живьём]**; доки там же |
| Лидерборд | `GET https://stats-data.hyperliquid.xyz/Mainnet/leaderboard` → HTTP 200, **39,2 МБ**, 47 370 строк (`ethAddress`, `accountValue`, `displayName`, `windowPerformances` по day/week/month/allTime: `pnl`, `roi`, `vlm`). Это адрес сайта статистики app.hyperliquid.xyz; **в официальной документации API его не нашёл** — считать неофициальным/нестабильным. | **[живьём]**; статус «официальный» — **UNVERIFIED** |
| Хранилища (vaults) | `{"type":"vaultSummaries"}` вернул `[]` | **[живьём]** |
| Лимиты | REST по IP: суммарный вес **1200 в минуту**; `clearinghouseState` — вес 2; `userFills` — вес 20 + доп. вес за каждые 20 возвращённых элементов; прочие info-запросы — 20. | https://hyperliquid.gitbook.io/hyperliquid-docs/for-developers/api/rate-limits-and-user-limits **[живьём]** |

Оценка нагрузки (вывод из лимитов): опрос `clearinghouseState` для 20 адресов раз в минуту = 20 × 2 = 40 веса/мин — малая доля из 1200. `userFills` по 20 адресам ≈ 20 × (20 + 100) = 2400 при полном ответе в 2000 записей — дорого, поэтому читать `userFillsByTime` с узким окном.

Наблюдение: у выбранного самого верхнего по дневному PnL адреса не было открытых позиций в момент запроса; живость данных проверена только по `time` ответа, не по качеству сигнала.

### C4. Polymarket

| Вопрос | Факт | Источник |
|---|---|---|
| Позиции пользователя | `GET https://data-api.polymarket.com/positions?user=<адрес>&limit=2` → 200, поля `size`, `avgPrice`, `currentValue`, `cashPnl`, `title`, `slug`, `redeemable`. | **[живьём]** |
| Сделки пользователя | `GET https://data-api.polymarket.com/trades?user=<адрес>&limit=2` → 200: `side`, `price`, `size`, `timestamp`, `title`, `outcome`, а также `name`, `pseudonym`, `bio` (псевдоним профиля). | **[живьём]** |
| Лидерборд | `GET https://data-api.polymarket.com/v1/leaderboard?timePeriod=DAY&orderBy=PNL&limit=3` → 200: `rank`, `proxyWallet`, `userName`, `vol`, `pnl`. | **[живьём]** |
| Активность | `GET …/activity?user=<адрес>&limit=2` → 200 | **[живьём]** |
| Доки | https://docs.polymarket.com/developers/misc-endpoints/data-api-get-positions (описание даёт базу `/v2/`, лидерборд с `time_period`; фактически проверен `/v1/leaderboard` — различия не разбирал). Лимиты и условия данных: подробно в `docs/research/polymarket_data_api.md` §6 (2026-10-01: `/v2/prices-history` 200 запросов / 10 с и т.д.), для data-api users/leaderboard отдельного лимита в прочитанном нет. | **UNVERIFIED** для лимитов positions/trades/leaderboard |

Применимость: только для рынков `PM*` (шансы), и только если ставка бота копируется как «купил Yes/No» → UP/DOWN по смене вероятности [вывод].

## D. Применимость к игре и риски (всё — выводы)

| Вопрос | Оценка |
|---|---|
| Задержка | Раунд 3 мин, ставка до окна. Позиции на OKX/Hyperliquid доступны по запросу практически сразу (опрос раз в минуту — достаточно). Но позиция, открытая 5 минут назад, — сигнал не о ближайших 3 минутах: копирование даёт «настроение», а не предсказание. |
| Отображение позиции в UP/DOWN | Длинная позиция по активу = UP, короткая = DOWN; величина позиции/плечо → плечо бота (с ограничением по рынку и тиру ставки в `EconomyRules`). Хороший сигнал: свежее изменение позиции (`openTime` на OKX, `userFillsByTime` на HL), а не само её наличие. |
| Что делать, если трейдер вне позиции | Резервный вариант: бот действует по ценовому правилу из A, либо пропускает раунд. Лидерборд-адрес без позиции встретился уже в первой проверке (HL), так что это частый случай, а не редкий. |
| Активы | Лид-трейдеры торгуют BTC/ETH/SOL/XRP и т.д., а у нас ещё `xyz_*` и `PM*`. Для HIP-3 `xyz_*` позиции читаются тем же `clearinghouseState` (формат coin с префиксом) — **не проверял**, UNVERIFIED. |
| Приватность | Реальные адреса и никнеймы в игре не показываем. Агрегация («72% копируемых трейдеров в лонге BTC») достаточна, если не показывать выборку из 3–5 человек. Адреса публичны на блокчейне/лидерборде (HL, Polymarket), но у OKX — псевдонимы; хранить в БД и логах только хеши, не публиковать. Юридическую оценку использования чужих псевдонимизированных данных не делал. |
| Зависимость от третьего лица | HL `/info` — официальный, с лимитами в доках; `stats-data.hyperliquid.xyz` — не в доках, 39 МБ, может исчезнуть. OKX публичные пути — в доках, но имена в доках расходятся с рабочими. Binance — нет официального пути. Решение: кэш + запасной ценовой режим (правила A), бот не должен падать при недоступности. |
| Этика/позиционирование | Бот «копирует трейдера X» — это маркетинг с оговорками; честнее называть «боты, обученные на поведении сообщества» только если так и есть. Решение за Алексом. |

## Что из этого реально и дёшево (по убыванию пользы к усилиям)

1. **Ценовые правила на 1-мин свечах из нашего сервера** (раздел A, 6 правил). Усилие: низкое — данные уже есть, формулы RSI/EMA/Bollinger/Donchian короткие, лицензионно чисто (реализуем идею, берём код только из MIT/Apache). Польза: высокая — боты впервые видят цену.
2. **Слой поведения из B**: плечо и ставка зависят от последнего результата бота (B5, B6), часть ботов «овертрейдит» (B1), распределение результатов с большинством в минусе (B8). Усилие: низкое. Польза: средняя-высокая.
3. **Hyperliquid как источник «настроения» (агрегат)**: `clearinghouseState` для ~20 адресов раз в минуту ≈ 40 веса из 1200/мин. Усилие: среднее. Польза: средняя; список адресов брать из `stats-data` лидерборда раз в сутки (неофициально).
4. **OKX публичные лид-трейдеры**: два проверенных эндпойнта. Усилие: среднее. Польза: средняя; псевдонимы людей, поэтому только агрегат.
5. **Polymarket top traders для `PM*`**: проверено, 3 эндпойнта. Усилие: среднее. Польза: средняя только для PM-рынков.
6. **Binance**: не делать — официального API нет, а недокументированные пути и скрейпинг рискованны (условия Binance.com не прочитаны).

## Не проверено

- Полный текст Terms of Use Binance.com (страница отдала только футер) и OKX/Bybit/Polymarket по использованию публичных данных.
- Существует ли у Bybit публичный эндпойнт чтения позиций мастер-трейдера (мой пробный путь был выдуман, 404 ничего не доказывает).
- Рабочий внутренний эндпойнт Binance Smart Money/Leaderboard (один пробный путь дал 404).
- Лимиты OKX public copy-trading; лимиты Polymarket data-api `positions`/`trades`/`leaderboard`.
- Читается ли `clearinghouseState` для HIP-3 (`xyz_*`) позиций; формат coin.
- Является ли `stats-data.hyperliquid.xyz` стабильным/разрешённым для автоматического использования.
- Лицензия vectorbt (NOASSERTION), полные тексты статей B (читал только аннотации/сниппеты), количественные данные по распределению плеч и круглым суммам ставок.
- Положительное ожидание любого из правил A на 3-минутном горизонте — не измерялось (для «похоже на человека» не требуется).
