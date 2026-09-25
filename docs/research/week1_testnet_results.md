# Неделя 1, день 2–4 — результаты на testnet11 (`chain/c2-cli`)

Дата — **2026-09-25**. Все действия ниже выполнены реальным CLI `c2` (`chain/c2-cli`, бинарник
`chain/target/debug/c2`) с кошельков `~/.c2/testnet11/{operator,counterparty}.json` против публичного
API `https://testnet11.api.coinset.org` (маршрут обоснован в
[`docs/research/week1_chain_access.md`](week1_chain_access.md) §0/§3). Источник команд —
[`docs/research/week1_testnet_cli.md`](week1_testnet_cli.md). Кошельки — стартовое состояние: `operator`
профинансирован фаусетом testnet11 (~1.00018 TXCH), `counterparty` — 0.

**Публичный explorer testnet11 не найден рабочим сегодня** (проверено `curl`, 2026-09-25):
`testnet11.spacescan.io` → `504 Gateway Timeout`; `www.spacescan.io` → `403`; `www.coinset.org` не
имеет отдельного testnet11-раздела (`/testnet11` → `404`), `testnet11.coinset.org` → `525` (TLS-ошибка
Cloudflare, тот же паттерн, что и `docs.coinset.org` в `week1_chain_access.md` §3). Поэтому для каждого
факта ниже вместо ссылки на explorer дан **coinset API запрос для воспроизведения**
(`POST https://testnet11.api.coinset.org/<method>`, JSON-тело как в примере).

---

## 1. Адреса

| Кошелёк | Адрес | Puzzle hash |
|---|---|---|
| `operator` | `txch1ksxy56805vz64929jj9c44spwzkr5ux96k9lf2e7m39lvczrm5rsgjn24u` | `0xb40c4a68efa305aa9545948b8ad60170ac3a70c5d58bf4ab3edc4bf66043dd07` |
| `counterparty` | `txch15ruuek2a7m6lees94gq5p28ykw05gp47jr4rhs88pj2p6lty6qfq6gqzvg` | `0xa0f9ccd95df6f5fce605aa0140a8e4b39f4406be90ea3bc0e70c941d7d64d012` |

Воспроизвести баланс любого адреса:
```
POST /get_coin_records_by_puzzle_hash
{"puzzle_hash":"0x<puzzle_hash>","include_spent_coins":true}
```

---

## 2. Таблица всех on-chain действий

Комиссии подбирались по `POST /get_fee_estimate {"target_times":[60],"cost":<измеренный cost dry-run>}`
(§5 ниже — точные цифры и один инцидент, где расчётной комиссии не хватило). Мемпул testnet11 весь
проход был заполнен на 60–100 % (`get_blockchain_state.mempool_cost` относительно
`mempool_max_total_cost=110 000 000 000`), из-за чего подтверждения занимали от ~1 до ~5.5 минут даже
при ненулевой комиссии — держать это в уме при повторе.

| # | Действие | Fee, mojo | Высота блока | UTC-время блока | Coin id / launcher id | Cost (total/byte/exec/cond) |
|---|---|---|---|---|---|---|
| 1 | `send` 0.1 TXCH `operator`→`counterparty` | 1 000 000 | **4 735 640** | 2026-09-25T16:06:19Z | потраченная монета `0x50e7b581...42ee7a43a`; новая монета `counterparty` `0x1640d23d...4930243fed99e06b` (100 000 000 000 mojo); сдача `operator` `0x7f8df5e8...8a30c9880342b09` (900 180 754 150 mojo) | 10 227 592 / 5 388 000 / 39 592 / 4 800 000 |
| 2 | `issue-play` (mint PLAY CAT) `operator` | 3 000 000 (первая попытка 1 000 000 отклонена, см. §5.1) | **4 735 648** | 2026-09-25T16:10:28Z | asset id (TAIL) `0x4b88ce97...ee716080b70`; eve-монета PLAY `0x37837f6d...276acb48a9d9e7d568` (100 000 000 000 mojo = 100 000 000 PLAY) | 38 741 857 / 31 764 000 / 377 857 / 6 600 000 |
| 3 | `offer-take` (offer: 1 000 PLAY ↔ 0.01 TXCH) `counterparty` | 4 500 000 | **4 735 660** | 2026-09-25T16:17:03Z | сдача PLAY `operator` (puzzle hash `0xfe0c1725...442042b2fd5972cc5`, 99 999 000 000 mojo); полученный PLAY `counterparty` (puzzle hash `0x3bf1d873...adf0c12cdfeeb2901`, 1 000 000 mojo); полученный TXCH `operator` `0x8959c9ce...299a509e1a78bc30a` (10 000 000 000 mojo) | 50 517 455 / 41 412 000 / 705 455 / 8 400 000 |
| 4 | `oracle-launch` (manager = `operator`) | 1 200 000 | **4 735 667** | 2026-09-25T16:19:48Z | launcher id `0xc5cb79a5...6033fec09b9cdeb20`; singleton puzzle hash `0xc3f0f83d...a121b5a7de5706ca3fb1f66d5` | 13 011 264 / 8 172 000 / 39 264 / 4 800 000 |
| 5 | `oracle-publish --from-hyperliquid` (sequence 1) `operator` | **0** (см. §5.3 — вторая, комиссионная попытка не вошла в блок) | **4 735 705** | 2026-09-25T16:30:20Z | потраченная eve-монета `0xd07b0304...455a0486d1882ec8e7abc8a06e1dc24`; новая монета поколения 2 `0x12e009fd...cbf13bba469e6e81` | 21 509 048 / 18 432 000 / 77 048 / 3 000 000 |

Запрос для проверки любой строки:
```
POST /get_coin_record_by_name  {"name":"0x<coin_id>"}
POST /get_block_record_by_height  {"height":<высота>}
```

---

## 3. PLAY CAT

- **Asset id (TAIL hash):** `0x4b88ce9712e97e73ad3759541fc23d38704e31d27d6586c28be14ee716080b70`
- **Тип TAIL:** genesis-by-coin-id, single-issuance (один-единственный минт возможен для этого TAIL).
- **Предложение (эмиссия, решить/пересмотреть при необходимости):** 100 000 000 PLAY = 1×10¹¹ mojo
  (1 PLAY = 1000 CAT-mojo, стандарт CAT2), обеспечено 0.1 TXCH (мохо CAT-монеты — те же мохо, просто
  «покрашенные» TAIL'ом, см. `docs/research/week1_testnet_cli.md` §7). Это on-chain казна для вывода
  PLAY игроками (решение Р-6, `docs/product/decisions.md`): каждый выведенный в кошелёк PLAY должен
  быть обеспечен ровно таким же количеством застейканных мохо на момент выпуска.
- **Проторговано через Offer:** 1 000 PLAY (1 000 000 CAT-mojo) отдано `operator` → `counterparty` за
  0.01 TXCH (10 000 000 000 mojo). См. строку 3 таблицы §2.
- **Балансы после торгов (проверено `get_coin_records_by_puzzle_hash`):**
  - `operator`: 99 999 000 000 mojo PLAY (99 999 000 PLAY), 10 000 000 000 mojo TXCH.
  - `counterparty`: 1 000 000 mojo PLAY (1 000 PLAY), 89 995 500 000 mojo TXCH
    (100 000 000 000 − 10 000 000 000 запрошенных TXCH − 4 500 000 fee).

Запрос для проверки PLAY-баланса любого адреса:
```
POST /get_coin_records_by_puzzle_hash
{"puzzle_hash":"0x<CatArgs::curry_tree_hash(asset_id, p2_puzzle_hash)>","include_spent_coins":false}
```
(`operator`: `0xfe0c1725a301a1ce3d05c1a3c5566854a349051112e5865442042b2fd5972cc5`;
`counterparty`: `0x3bf1d8736a85b667d584b159d4dbe26f2dba6d9dc7e919fadf0c12cdfeeb2901`)

---

## 4. Оракул (Р-11)

- **Launcher id:** `0xc5cb79a5ea20fb66a29c63867806dec4d1120fa002b386c6033fec09b9cdeb20`
- **Singleton puzzle hash** (не меняется между поколениями, manager-ключ = `operator`):
  `0xc3f0f83d3c994ee4f14d50496e78eb65ff4c999a121b5a7de5706ca3fb1f66d5`
- **Опубликовано (поколение 1, подтверждено on-chain):**

  | Поле | Значение |
  |---|---|
  | price | **8 396 500** центов ($83 965.00) |
  | timestamp | **1790353493** (unix) = 2026-09-25T16:24:53Z |
  | sequence | **1** |
  | источник цены | Hyperliquid `POST https://api.hyperliquid.xyz/info {"type":"metaAndAssetCtxs"}`, поле `assetCtxs[i].markPx` для `meta.universe[i].name == "BTC"` (индекс найден по имени, не захардкожен — см. `chain/c2-cli/src/hyperliquid.rs`, тесты `finds_btc_by_name_not_index`) |

  Значение подтверждено чтением реального CLVM-solution потраченной монеты
  (`POST /get_puzzle_and_solution {"coin_id":"0xd07b0304...","height":4735705}`) и ручным
  декодированием: `0x00801ed4` → 8 396 500, `0x6ab6a055` → 1790353493 — байт-в-байт совпадает с тем,
  что напечатал CLI при первой (в итоге подтверждённой) попытке публикации.

Запрос для проверки announcement-монеты текущего поколения:
```
POST /get_coin_record_by_name  {"name":"0x12e009fd74dcd74c6911b48c0ad8ed8cdba5fc8961acc15bcbf13bba469e6e81"}
```

---

## 5. Измеренные затраты и два найденных/исправленных бага

### 5.1 `issue-play`: комиссия «слишком близко к нулю»

Первая попытка (`--fee 1000000`, cost=38 741 857, т.е. fee/cost≈0.0258) отклонена coinset'ом:
`push_tx failed: ... error INVALID_FEE_TOO_CLOSE_TO_ZERO`. Мемпул testnet11 был заполнен на 99.99 %
(`mempool_cost` 109 990 650 120 / 110 000 000 000), и `get_fee_estimate` для этого cost на тот момент
давал ~0.0677 mojo/cost для попадания за 30–120 с. Повтор с `--fee 3000000` (fee/cost≈0.0774) —
принят, подтверждён за один прогон. **Диагностика**: `min_fee` в перегруженном мемпуле — не
фиксированное число, а функция от текущей загрузки; надёжнее всего дёргать `get_fee_estimate` с
реальным cost из dry-run прямо перед push, а не полагаться на условно «небольшую» комиссию.

### 5.2 `oracle-launch`: билдер терял сдачу — реальная потеря ~0.8 TXCH на комиссию

**Инцидент.** `find_funding_coin` берёт первую монету с суммой **не меньше** нужной — у `operator`
на тот момент было две непотраченные монеты (900 180 754 150 mojo сдача от `issue-play` за вычетом... 
на самом деле 800 177 754 150 mojo сдача от `issue-play`, и отдельно 10 000 000 000 mojo — платёж,
полученный по офферу). `find_funding_coin` выбрал бОльшую (800 177 754 150 mojo). Старая версия
`build_oracle_launch` не добавляла условие сдачи: весь остаток монеты сверх `SINGLETON_AMOUNT` (1 mojo)
consensus считает **неявной комиссией** (потраченная, но не пересозданная стоимость — без ошибки, это
штатное поведение Chia). Транзакция подтвердилась штатно (строка 4 таблицы §2, `--fee 1200000`), но
реально «комиссией» ушло **≈800 176 554 149 mojo (≈0.8 TXCH)** — почти весь баланс `operator`. Баланс
`operator` упал с 810 177 754 150 до ровно 10 000 000 000 mojo (проверено `c2 balance`/coinset сразу
после).

**Факт подтверждён on-chain**: монета `0x7f8df5e8...8a30c9880342b09` (800 177 754 150 mojo) потрачена в
блоке 4 735 667 (launch), новых монет `operator` в этом блоке нет — весь остаток ушёл в `fees` блока.

**Исправление (сделано в этом проходе, до дальнейших push):** `build_oracle_launch`
(`chain/c2-cli/src/builders/oracle.rs`) теперь принимает `fee: u64` явно и возвращает
`change_amount`/создаёт `create_coin` со сдачей, когда `funding_coin.amount > SINGLETON_AMOUNT + fee` —
тот же паттерн, что уже был в `issue-play`/`send`. Регрессионный тест
`oracle_launch_returns_change_for_excess_funding` (`chain/c2-cli/tests/oracle_singleton.rs`) с ровно
теми же числами инцидента (800 177 754 150 / fee 1 200 000) проверяет, что сдача возвращается, а не
исчезает. `cargo test --workspace` зелёный (27/27) **после** этого фикса.

**Вывод:** билдеры, которые тратят монету, найденную по «сумма ≥ минимум» (а не заранее точного
размера), обязаны явно обрабатывать сдачу — иначе любой излишек на funding-монете молча становится
комиссией. Для будущих недель — то же самое нужно перепроверить в любом новом билдере, который берёт
монету через `find_funding_coin`.

### 5.3 `oracle-publish`: кеш состояния обновлялся при dry-run + гонка с забуксовавшим 0-fee пушем

Второй, более мелкий баг того же прохода: `cmd_oracle_publish`/`cmd_issue_play`/`cmd_oracle_launch`
сохраняли `~/.c2/testnet11/<name>.oracle.json`/`.play.json` **безусловно**, даже при dry-run (без
`--push`) — то есть простой dry-run продвигал закэшированный `next_sequence`/`current_coin` на
транзакцию, которая никогда не уходила в сеть. Пойман сразу на первом dry-run `oracle-publish
--from-hyperliquid` этого прохода (кеш продвинулся до `sequence=2`, указывая на монету, которой ещё не
существовало on-chain). Кеш вручную восстановлен до состояния eve-монеты; **исправлено в коде**: все
три команды теперь сохраняют кеш только после успешного `push_bundle` (`main.rs::maybe_push` возвращает
`bool`, вызывающий код обновляет кеш только если `pushed == true`).

Отдельно от этого бага — реальная гонка на самом мемпуле: первый push `oracle-publish
--from-hyperliquid --fee 0` вернул `status=SUCCESS` (принят в мемпул), но не подтверждался ~5.5 минут
(мемпул был заполнен на 99.998 %). Не дождавшись подтверждения за разумное время, была собрана вторая,
комиссионная попытка (`--fee 1800000`, через отдельную «только-комиссия» монету-спутник — см.
`chain/c2-cli/src/builders/fee.rs`, добавлен в этом проходе, т.к. пересоздаваемый синглтон-оракул
всегда несёт ровно 1 mojo и не может сам зарезервировать комиссию, не сломав собственный баланс) —
coinset вернул `status=PENDING`. В итоге **в блок вошла первая (0-fee) попытка** (блок 4 735 705, см.
§4) — вторая осталась orphan-спендом той же уже потраченной монеты, её монета-комиссия
(10 000 000 000 mojo) реально не тратилась (проверено — она и сейчас непотрачена, баланс `operator`
после всего прохода равен ровно этой сумме). Потерь не было, но урок: на перегруженном testnet11
мемпуле 0-fee транзакция может ждать минуты, и «подстраховочный» fee-bump конкурирует с уже
принятой — обе могут в итоге not conflict только если первая действительно не попадёт в блок; в этот
раз ставка на ожидание была бы достаточна и без второй попытки, но заранее это было не различить.

---

## 6. Статус kill-критериев недели 1 (`PLAN.md` §6)

| # | Критерий | Статус |
|---|---|---|
| 1 | Калькулятор ликвидации проходит юнит-тесты на исторических ценах | **Выполнено** (вне скоупа этого прохода — `engine/`; факт по `engine/target/surefire-reports/TEST-crowdvsmarket.engine.margin.LiquidationCalculatorGoldenVectorTest.xml`: `tests="120" errors="0" skipped="0" failures="0"`) |
| 2 | Один announcement-спенд реально прошёл на testnet11 | **Выполнено** — блок 4 735 705, launcher `0xc5cb79a5...`, price/ts/seq в §4 |
| 3 | PLAY CAT выпущен и переторгован через Offer на testnet11 | **Выполнено** — asset id `0x4b88ce97...`, сделка в блоке 4 735 660, §2–3 |

Все три критерия недели 1 закрыты; сужение скоупа (`PLAN.md` §6, действие при провале) не требуется.

---

## 7. `cargo test --workspace`

Зелёный после всех изменений этого прохода — **27 тестов, 27 passed, 0 failed**
(было 16 в начале прохода: +6 `send` (2 builder-юнит + 2 simulator + доработка `offer_play_xch.rs` на
частичный оффер), +4 `hyperliquid` (парсинг/цены), +2 `fee` (builder-юнит), +1 регрессия
`oracle_launch_returns_change_for_excess_funding`; итоговая арифметика — новые тесты добавлялись и
удалялись/объединялись по ходу правок, финальное число проверено последним прогоном).

```
cd chain && cargo test --workspace
```

Изменения в коде этого прохода — `chain/c2-cli/src/{main.rs,hyperliquid.rs,builders/{send.rs,fee.rs,offer.rs,oracle.rs}}`,
новые тесты `chain/c2-cli/tests/send.rs` + правки `tests/{offer_play_xch.rs,oracle_singleton.rs}`.
Подробности команд — `chain/README.md` (обновлён) и `docs/research/week1_testnet_cli.md` (не менялся,
описывает состояние CLI на начало дня 2; этот файл документирует, что добавилось на дне 2-4).
