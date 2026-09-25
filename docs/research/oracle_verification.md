# Публичная проверка оракула — `tools/verify-oracle` (Р-11/Р-40/Р-41/Р-47)

Дата — **2026-09-26**. Инструмент: [`tools/verify-oracle/verify_oracle.py`](../../tools/verify-oracle/verify_oracle.py)
(английские README/докстринг там же — инструмент рассчитан на внешнего аудитора, не только на
эту команду). Источник решений, которые он проверяет — [`docs/product/decisions.md`](../product/decisions.md):
Р-11 (минимальный синглтон-оракул), Р-40 (публикация раз в час на границе часа), Р-41
(в ту же публикацию входит хеш итогов раунда — «любой может сверить, что их не меняли задним
числом»), Р-47 (в тестовом режиме оракул публикует по-настоящему на каждой границе раунда).
Формат canonical results.json и `resultsHash` — «FIXED INTERFACE (2)» в
[`server/src/main/java/crowdvsmarket/server/util/ResultsHasher.java`](../../server/src/main/java/crowdvsmarket/server/util/ResultsHasher.java).

Это ответ на задачу второй волны тестирования: «провести серию тестирований снова» перед
приглашением независимого критика. Предыдущий проход (wave-2, эта же сессия, scratchpad) уже
доказал сам подход (питоновский скрипт на `chia_rs` + публичный `coinset.org` API); здесь тот
подход оформлен в постоянный, самостоятельный инструмент в репозитории, плюс добавлена сверка
со стендом, которой в wave-2 не было.

## 1. Что инструмент проверяет — и что нет

**Проверяет:**

1. **Родословную синглтона** — от launcher-монеты вперёд по публичному API `coinset.org`
   (`get_coin_record_by_name`/`get_coin_records_by_parent_ids`/`get_puzzle_and_solution`, без
   ключей, без нашего сервера) — что это одна цепочка без развилок (у каждой монеты ровно один
   ребёнок).
2. **Каждый `CREATE_PUZZLE_ANNOUNCEMENT` каждого спенда** — раскодирован (реальным CLVM-рантаймом
   `chia_rs`, прогоном on-chain puzzle_reveal против его же on-chain solution — не эмуляцией) в
   6-элементный список `(seq, ts, priceCents, resultsHash, sourceFlag, sourceTs)`.
3. **Учёт по `seq`**: первое появление (высота + монета), повторы (совпадающие поля — не проблема,
   это ожидаемый повторный паблиш из durable-очереди `chainsvc` после гонки reconciliation;
   различающиеся поля под тем же `seq` — **CONFLICT**, две разные «истины» под одним номером), и
   разрывы в последовательности `seq` (номер, для которого якорь так и не попал в цепь).
4. **(при `--stand-url`) Сверку со стендом**: для каждого `seq` из цепи находит соответствующий
   раунд через публичный API стенда (см. §2 ниже), забирает его `GET /api/rounds/{id}/results.json`
   и проверяет `sha256(байты) == resultsHash` из цепи и `closePriceCents == priceCents` из цепи.

**Не проверяет** (явно, а не по умолчанию):

- Что опубликованная цена сама была честной ценой BTC — доверие ключу оператора и Hyperliquid как
  источнику это осознанный, задокументированный компромисс (Р-11, `PLAN.md` §2), а не то, что
  проверяет этот инструмент.
- Что выплаты внутри `results.json` посчитаны верно движком — это зона юнит-тестов `engine/`.
- Подписи `AGG_SIG_ME` каждого спенда — публичная цепь уже отвергла бы любой спенд, не прошедший
  эту проверку, до его подтверждения; проверять это заново здесь означало бы просто повторно
  доверять тем же публичным данным цепи, которые инструмент и так читает.

## 2. Как раунд сопоставляется с `seq` (отдельного публичного API нет — как нашли обходной путь)

Готового `GET /api/rounds/by-seq/{seq}` в проекте нет. Инструмент находит соответствие сам:

- `roundId` детерминирован: `RoundWindow.roundId() = "<код интервала>-<startMs>"`
  (`engine/src/main/java/crowdvsmarket/engine/round/RoundWindow.java`), где `startMs` идёт
  фиксированным шагом от момента первого старта стенда (Р-19: «всегда один идущий и один открытый
  для ставок раунд каждого типа»).
- Инструмент публичным `POST /api/rpc/rounds {"args":["HOURLY"]}` (и `"DAILY"`) узнаёт окно
  **текущего** идущего раунда (без всякой сессии — `round`/`rounds` не входят в `requireSignedIn`
  в `RpcResource.java`) — это даёт один заведомо верный `startMs` и шаг серии
  (`endMs − startMs`).
- Дальше шагает `roundId` назад на этот шаг и на каждом шаге зовёт публичный
  `POST /api/rpc/round {"args":["<roundId>"]}`. Любой ещё сохранённый в базе стенда
  settled-раунд отвечает DTO с полем `oracleAnchor: {seq, state, height}`
  (`Rounds.RoundSettled.oracleAnchor`, собирается из
  `OracleRecordRepository.findAnchorByRoundId` — колонка `oracle_records.round_id` там уже есть,
  просто не выставлена отдельной ручкой) — это и есть публичное сопоставление `seq ↔ roundId`, только
  не оформленное как отдельный lookup. Инструмент останавливает обратный обход серии на первом
  `roundId`, на который стенд отвечает 404 (реально до начала истории стенда либо гонка с ещё не
  досчитанным раундом) — горстка старых `seq` за этим горизонтом не считается находкой, это просто
  история, которую инструмент не стал угадывать.

**Предложение**, если когда-нибудь захочется сделать это отдельной первоклассной ручкой вместо
производного обхода: `GET /api/rounds/by-seq/{seq}`, тот же набор полей (`roundId`, `oracleAnchor`,
`resultsHash`), тонкий `SELECT` над уже существующей колонкой `oracle_records.round_id` — новое
состояние не нужно. Ничего в этом инструменте от этого предложения не зависит — обход выше уже
даёт полное и верное сопоставление для каждого раунда, который стенд ещё хранит.

## 3. Как запустить

Зависимость — один пакет, не входящий в stdlib: [`chia_rs`](https://pypi.org/project/chia_rs/),
официальная реализация CLVM от Chia Network с питоновской обвязкой (используется только чтобы
прогнать on-chain puzzle_reveal каждого спенда против его же on-chain solution — чистое вычисление
над уже публичными данными, без ключей и без собственных сетевых вызовов). Есть готовые wheel'ы
вплоть до самых новых Python (проверено — ставится и на Python 3.14).

```bash
cd tools/verify-oracle
pip install -r requirements.txt

# только цепь (testnet11 по умолчанию):
python3 verify_oracle.py c5cb79a5ea20fb66a29c63867806dec4d1120fa002b386c6033fec09b9cdeb20

# цепь + сверка с локальным стендом (docs/product/local_stand.md):
python3 verify_oracle.py c5cb79a5ea20fb66a29c63867806dec4d1120fa002b386c6033fec09b9cdeb20 \
    --stand-url http://localhost:8480 --json-out result.json
```

`c5cb79a5ea20fb66a29c63867806dec4d1120fa002b386c6033fec09b9cdeb20` — launcher id оракула этого
проекта (неделя 1, `chain/README.md`); первый аргумент — любой launcher id любого синглтона на
выбранной сети (`--network mainnet`), инструмент не завязан на этот конкретный проект.

## 4. Как читать вывод

- В stdout — человекочитаемая сводка; `--json-out FILE` дополнительно пишет полный JSON (каждое
  поколение, каждое announcement, полная история каждого `seq`, детали сверки со стендом).
- **Здоровый** прогон: `CONFLICTS: 0`, `Gaps in the seq sequence: 0`, и (при `--stand-url`)
  `Mismatches: 0`. Ненулевые `Benign duplicates` — это нормально (см. §1).
- Код выхода: `0` — чисто (0 конфликтов, 0 расхождений); `1` — прогон завершился, но нашёл хотя бы
  один CONFLICT или расхождение (детали — в `CONFLICT DETAILS`/`MISMATCH DETAILS`); `2` — ошибка
  окружения/аргументов (неверный launcher id для этой сети, не установлен `chia_rs`, `coinset`
  недоступен).
- Одно раннее announcement (самый первый спенд синглтона, неделя 1, ещё до Р-41) использует
  сверх-старый 3-элементный формат без `resultsHash` — инструмент честно показывает его отдельно
  под `Unparsed announcement-shaped conditions`, а не путает с коллизией `seq`. Это ожидаемая
  история эволюции формата, не находка. Раскодировано вручную для протокола: `(priceCents=8 396 500,
  ts=1790353493, seq=1)` — то же самое первое announcement недели 1, что задокументировано в
  `PLAN.md` §1 («блок 4 735 705, price 8 396 500 ¢, ts 1790353493, seq 1»).

## 5. Реальный прогон 2026-09-26 — testnet11 + `http://localhost:8480`

Стенд (`docker compose -p herd`, `docs/product/local_stand.md`) на момент прогона: `herd-server-1`/
`herd-chainsvc-1`/`herd-web-1`/`herd-db-1` все healthy, тестовый режим (`replay`, ускорение ×20,
часовой раунд = 180 с, дневной = 900 с) уже несколько часов непрерывно публиковал оракул в
testnet11 (Р-47).

Команда:

```bash
python3 tools/verify-oracle/verify_oracle.py \
    c5cb79a5ea20fb66a29c63867806dec4d1120fa002b386c6033fec09b9cdeb20 \
    --stand-url http://localhost:8480 --json-out result.json
```

Вывод (человекочитаемая часть; журнал обхода по поколениям в stderr опущен здесь для краткости —
71 поколение, все совпадают со связкой прошлого прохода wave-2):

```
Launcher id:        0xc5cb79a5ea20fb66a29c63867806dec4d1120fa002b386c6033fec09b9cdeb20
Network:            testnet11 (https://testnet11.api.coinset.org)
Generations walked: 71
Lineage errors:     none (single unforked chain from launcher to tip)

Distinct seq numbers on chain: 98 (range 1..98)
Benign duplicates (re-announced, identical content): 86
CONFLICTS (re-announced, DIFFERENT content):         0
Gaps in the seq sequence:                            0

Unparsed announcement-shaped conditions (not our 6-field schema): 1
  generation=0 coin=0xd07b0304e76bcf3a138dc2133d36fb991455a0486d1882ec8e7abc8a06e1dc24 height=4735705: expected 6-element announcement list, got 3 (raw_hex=ff8400801ed4ff846ab6a055ff0180…)

Stand cross-check against: http://localhost:8480
Settled rounds discovered on stand with an oracle anchor: 99
Rounds checked (chain seq -> stand results.json):         98
  hash matches:  98
  price matches: 98
Mismatches:                                                0
```

**Сверка с ожиданием задачи** («76 благотворных дубликатов, 0 конфликтов, 0 разрывов после
бэкфилла»): в wave-2 (снимок на 2026-09-25, до бэкфилла) тот же обход давал **76 благотворных
дубликатов, 0 конфликтов, но 1 разрыв** (пропущен `seq 85`, диапазон 1..91). Этот прогон, сделанный
позже (2026-09-26, после бэкфилла и нескольких лишних часов непрерывной публикации в тестовом
режиме — отсюда диапазон вырос до 1..98, а число дубликатов до 86, это ожидаемо при большем числе
раундов) — **0 разрывов, 0 конфликтов**, полностью на диапазоне `1..98`. Расхождение с точной
цифрой «76» — не проблема: это то же самое здоровое состояние (0/0), просто снятое позже на большем
числе прошедших раундов; равенство «0 разрывов, 0 конфликтов» — то, что было условием, а не точная
цифра дубликатов, которая по конструкции растёт со временем.

Сверка со стендом: 99 settled-раундов с якорем `oracleAnchor` найдено на стенде (83 часовых + 16
дневных), 98 из них сверены с цепью (99-й — ещё не подтверждённая в цепи запись,
`pendingRecords`/`state != "confirmed"`, ожидаемо на непрерывно работающем стенде), **все 98 —
совпадение и по `resultsHash`, и по `closePriceCents`**.

Полный JSON этого прогона (`result.json`, 71 поколение × до 20 announcement на поколение + 98
записей по `seq` + 99 записей по раундам) не закоммичен в репозиторий — как и другие
воспроизводимые прогоны в этом проекте (`docs/research/week1_testnet_results.md`), здесь
зафиксированы точная команда и её вывод для самостоятельного повтора, а не сырой дамп; повторить
можно в любой момент командой из §3 (числа будут расти по мере продолжения тестового режима на
стенде — это ожидаемо, не расхождение).

## 6. Ссылки

- [`tools/verify-oracle/verify_oracle.py`](../../tools/verify-oracle/verify_oracle.py),
  [`tools/verify-oracle/README.md`](../../tools/verify-oracle/README.md) — сам инструмент
  (докстринг в `.py` — самое полное описание протокола проверки).
- [`docs/product/local_stand.md`](../product/local_stand.md) §4 — как ещё смотреть оракул на
  testnet11 руками (без этого инструмента), включая launcher id и прямые `curl` к `coinset.org`.
- [`docs/product/decisions.md`](../product/decisions.md) Р-11/Р-40/Р-41/Р-47.
- [`server/src/main/java/crowdvsmarket/server/util/ResultsHasher.java`](../../server/src/main/java/crowdvsmarket/server/util/ResultsHasher.java) —
  каноническая форма `results.json` и `resultsHash`.
- [`server/src/main/java/crowdvsmarket/server/persistence/OracleRecordRepository.java`](../../server/src/main/java/crowdvsmarket/server/persistence/OracleRecordRepository.java) —
  откуда берётся `oracleAnchor` на DTO раунда.
