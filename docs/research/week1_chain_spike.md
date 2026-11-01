# Week 1, день 1 — компайл-спайк Chia Rust SDK на офлайн-симуляторе

Дата: 2026-09-25. Автор: сессия-агент (задача «chain/ + docs/research/week1_chain_spike.md», `the project plan` §5 день 1). Источник задачи: `the project plan` §2, §3, §5, §8.1; `docs/research/c2_deep_review.md` §3c («компайл-спайк — первый практический шаг недели 1, ранее не выполнялся»).

Код: [`chain/`](../../chain/) (workspace `chain/Cargo.toml`, единственный член `chain/sim-spike`). Все внешние факты в этом файле — со ссылкой на `curl crates.io/api/v1/...` (fetched 2026-09-25) или на прочитанный исходник пакета из `~/.cargo/registry/src` после `cargo build` (тоже 2026-09-25, тот же проход). Сеть использовалась только для скачивания зависимостей с crates.io; ни mainnet, ни testnet11 не затрагивались.

## 1. Итог одной строкой

Компайл-спайк выполнен и прошёл: workspace собирается, 5/5 тестов зелёные — (a) фандинг/спенд стандартной p2-монеты, (b) выпуск PLAY CAT (genesis-by-coin-id TAIL) и частичный перевод, (c) паттерн «managed-резолвер-синглтон» с 3 спендами, puzzle-announcement и независимым консьюмером в одном бандле + измерение CLVM-стоимости + негативный тест (неверный announcement отклоняется симулятором), (d) Offer PLAY↔XCH сделан и взят офлайн. Открытый вопрос §8.1 (нужна ли полная нода) этим спайком не закрыт — симулятор офлайн и полностью in-memory, вопрос про подключение к пирам остаётся на день 2–3, когда потребуется реальный testnet11.

## 2. Версии с crates.io (`curl -H "User-Agent: ..." https://crates.io/api/v1/crates/<name>`, fetched 2026-09-25)

| Крейт | Последняя версия на crates.io | Дата публикации последней | Запиненная версия в `chain/sim-spike/Cargo.toml` | Дата публикации запиненной | Скачиваний (всего, на момент fetch) |
|---|---|---|---|---|---|
| `chia-wallet-sdk` | 0.36.0 | 2026-08-19T19:54:16Z | **0.36.0** | 2026-08-19T19:54:16Z | 81 964 |
| `chia-sdk-test` | 0.36.0 | 2026-08-19T19:53:05Z | **0.36.0** | 2026-08-19T19:53:05Z | 70 499 |
| `chia-sdk-driver` | 0.36.0 | 2026-08-19T19:52:36Z | **0.36.0** | 2026-08-19T19:52:36Z | 74 398 |
| `chia-sdk-types` | 0.36.0 | 2026-08-19T19:51:37Z | **0.36.0** | 2026-08-19T19:51:37Z | 83 898 |
| `chia-sdk-client` | 0.36.0 | 2026-08-19T19:52:04Z | **0.36.0** | 2026-08-19T19:52:04Z | 73 250 |
| `chia-protocol` | **0.50.0** | 2026-09-23T14:10:38Z | **0.36.1** | 2026-01-15T17:29:33Z | 294 984 (крейт в целом) |
| `chia-puzzles` | 0.20.3 | 2025-10-14T12:49:03Z | **0.20.3** | 2025-10-14T12:49:03Z | 292 755 |
| `clvmr` | **0.21.0** | 2026-09-22T13:04:22Z | **0.16.4** | 2026-01-26T14:17:46Z | 610 005 (крейт в целом) |
| `chia-consensus` | **0.50.0** | 2026-09-23T14:10:49Z | **0.36.1** | 2026-01-15T17:29:43Z | 272 122 (крейт в целом) |

**Почему запиненные версии `chia-protocol`/`chia-consensus`/`clvmr` не самые свежие**: `chia-wallet-sdk 0.36.0` (и `chia-sdk-test`/`chia-sdk-driver` той же версии) сами требуют `chia-protocol ^0.36.1`, `chia-consensus ^0.36.1`, `clvmr ^0.16.2` (проверено `curl crates.io/api/v1/crates/chia-sdk-test/0.36.0/dependencies` и аналогично для `chia-sdk-driver`/`chia-wallet-sdk`, fetched 2026-09-25). Указать в воркспейсе более новые 0.50.0/0.21.0 значило бы держать в графе зависимостей два несовместимых набора типов (`Coin`, `Allocator` и т.д. из разных версий крейта не совпадают как типы Rust) — компиляция такого workspace прошла бы (Cargo допускает несколько версий одного крейта в графе), но передать значение из кода на базе `chia-wallet-sdk` в код на базе `chia-protocol 0.50.0` напрямую было бы нельзя. Поэтому все версии в `Cargo.toml` синхронизированы с тем, что фактически требует `chia-wallet-sdk 0.36.0`.

**`clvmr = 0.16.5` — YANKED**: обнаружено на первом `cargo build` (`error: failed to select a version for the requirement clvmr = "=0.16.5"` → `version 0.16.5 is yanked`). Использована `0.16.4` (последняя не-yanked версия в диапазоне `^0.16.2`, опубликована 2026-01-26T14:17:46Z).

**`chia-sdk-test` загрузок**: `c2_deep_review.md` §3c цитирует 70 461 загрузку (сессия от 2026-09-25, более ранний проход в тот же день); в этом проходе `curl` вернул 70 499 — разница в 38 загрузок за несколько часов того же дня, не расхождение источников, а естественный прирост счётчика.

## 3. Workspace

`chain/Cargo.toml` — `[workspace]`, `resolver = "2"`, один член `sim-spike`, `edition = "2024"` (в `[workspace.package]`). Тулчейн: `rustc 1.98.1` / `cargo 1.98.1` (уже установлен, `rustc --version`, 2026-09-25) — поддерживает edition 2024 (стабилизирован с 1.85), так что задача «edition 2024 если тулчейн поддерживает» выполнена буквально, зависимости самого SDK при этом остаются на своих собственных edition (2021 у большинства чиа-крейтов) — это не конфликтует, у каждого крейта в графе своя edition.

`chain/sim-spike/Cargo.toml` — все версии зависимостей закреплены точно (`=x.y.z`). `Cargo.lock` присутствует в рабочей директории (лежит в `chain/Cargo.lock`) и не подпадает под `.gitignore` (там игнорируется только `chain/target/`) — то есть ничто не мешает его закоммитить. Сам коммит, однако, не сделан: в репозитории пока нет ни одного коммита (`git log` → `fatal: your current branch 'main' does not have any commits yet`, проверено 2026-09-25).

Фича-флаги `chia-wallet-sdk`: включены `action-layer` (нужна для п. (e) — RewardDistributor/Action Layer драйверы) и `chip-0035` (её требует `action-layer`). Полный список фич обнаружен через `curl crates.io/api/v1/crates/chia-wallet-sdk/0.36.0` (поле `version.features` в ответе JSON), не из документации — в крейте нет фичи `offers`, работа с Offer доступна без фиче-флагов (это часть `chia-sdk-driver` по умолчанию).

## 4. Сборка: время и результаты

Команда — буквально как в задаче: `cargo clean; time cargo test`.

| | Время (wall) | Комментарий |
|---|---|---|
| Холодная сборка + тесты (`cargo clean && cargo test --workspace`) | **≈20.8 с** (`20.823 total`, `95.03s user` / `14.97s system` при 528% CPU — то есть параллельная компиляция на ~5–6 ядрах; перезамерено 2026-09-25 после правки `measure_bundle_cost`/добавления негативного теста, см. §9 и §7) | Зависимости уже лежали в `~/.cargo/registry` (скачаны при первой попытке сборки этой же сессии) — это компиляция «с нуля», а не скачивание «с нуля». Отдельного замера с полностью пустым `~/.cargo/registry` не делалось (не было причины удалять локальный кэш, задача не просила именно этого). |
| Тёплый прогон (`cargo test --workspace` без `clean`) | **≈0.4 с** (`0.404 total`) | Инкрементальная сборка не нашла изменений, просто перезапустила уже собранные тестовые бинарники. |

Все 5 тестов — зелёные (`cargo test --workspace -- --nocapture`, полный вывод сохранён в ходе сессии, перепрогнано 2026-09-25):

```
test issue_play_cat_and_transfer_part ... ok
test play_cat_for_xch_offer_round_trip ... ok
test oracle_singleton_consumer_rejects_wrong_announcement ... ok
test oracle_singleton_three_price_updates ... ok
test fund_and_spend_standard_coin ... ok
```

(5-й тест, `oracle_singleton_consumer_rejects_wrong_announcement`, — негативный тест, добавлен позже дня 1, см. §7.)

## 5. Тест (a) — стандартная p2-монета: фандинг и спенд

Файл: [`chain/sim-spike/tests/standard_coin.rs`](../../chain/sim-spike/tests/standard_coin.rs).

`chia_sdk_test::Simulator::bls(amount)` создаёт монету напрямую на p2-puzzle-hash заданного BLS-ключа (без фаусета, без сети — фандинг просто вставляется в память симулятора). `chia_sdk_driver::StandardLayer::new(pk).spend(ctx, coin, conditions)` строит стандартный p2-спенд; `sim.spend_coins(ctx.take(), &[sk])` подписывает и проводит бандл через валидацию симулятора (та же `chia_consensus`-валидация, что и у полной ноды, см. §7). Тест прогоняет полный цикл: монета создана → потрачена сама на себя → у монеты появился один потомок того же номинала. Паттерн взят из собственного теста SDK `chia-sdk-driver-0.36.0/src/layers/standard_layer.rs::test_flash_loan`.

## 6. Тест (b) — PLAY CAT: выпуск и частичный перевод

Файл: [`chain/sim-spike/tests/cat_play.rs`](../../chain/sim-spike/tests/cat_play.rs).

TAIL — `chia_puzzle_types::cat::GenesisByCoinIdTailArgs` (genesis-by-coin-id, единственная эмиссия: TAIL зашивает id конкретной потраченной монеты, повторно её потратить нельзя, значит допечатать CAT тоже нельзя). Драйвер — `Cat::single_issuance(ctx, parent_coin_id, hidden_puzzle_hash, amount, extra_conditions)` из `chia_sdk_driver`, готовый метод, ничего писать не пришлось (ровно как предполагает `the project plan` §3: «Ничего нового писать не нужно»). После выпуска — `Cat::spend_all` с двумя `CreateCoin` в условиях p2-спенда (часть себе, часть «Бобу»), т.е. обычный CAT-спенд с двумя выходами.

**Важная деталь, найденная эмпирически**: монета, которая финансирует эмиссию (`sim.bls(N)`), должна иметь номинал **ровно равный** объёму эмиссии `N`, а не любой ненулевой номинал — она не «платит» за CAT, а **становится** eve-CAT-монетой 1:1 (эта монета консьюмится, а её родительский spend создаёт монету того же номинала по CAT-puzzle-hash). Несовпадение номиналов даёт `ErrorCode::MintingCoin` (создание монеты не из подтверждённого источника — консенсус-валидация симулятора та же, что у ноды, эта ошибка не специфична для симулятора). Наступили на эти грабли в тесте (d) при первой попытке (см. §8) — там же и разобрано подробнее.

Результат прогона (собственные данные симулятора, не из внешнего источника):
```
PLAY asset id: 92d5068a51cc50668c6307546dc8f4c39ac124975666e9441822a4cc3a2abb0e
PLAY genesis coin id (funding coin): c8125927409e2dde08c71fff464c873e97ed10c6ccdcb4a76b4f3245ed02e0f5
```
(supply 1 000 000, перевод 250 000 «Бобу», сдача 750 000 Алисе — обе дочерние монеты видны в состоянии симулятора после спенда).

## 7. Тест (c) — managed-резолвер-синглтон (паттерн оракула)

Файл: [`chain/sim-spike/tests/oracle_singleton.rs`](../../chain/sim-spike/tests/oracle_singleton.rs).

Реализация — минимальная форма паттерна `RewardDistributorType::Managed` из `the project plan` §3 (без Action Layer, просто синглтон с внутренним p2-паззлом одного «менеджерского» ключа): `chia_sdk_driver::SingletonLayer<StandardLayer>` поверх `chia_sdk_driver::Launcher`. Паттерн композиции подсмотрен в собственном тесте SDK (`chia-sdk-driver-0.36.0/src/layers/p2_singleton_layer.rs::test_p2_singleton_layer`), не выдуман с нуля.

Три спенда подряд; на каждом:
1. внутренний p2-спенд менеджера создаёт `CREATE_COIN` обратно на тот же inner-puzzle-hash (self-recreate — синглтон-топ-уровня сам оборачивает это в полный puzzle hash следующего синглтона) и `CREATE_PUZZLE_ANNOUNCEMENT`, message которого — сырые CLVM-байты сериализованного списка `(price timestamp sequence)` (`ctx.alloc(&vec![price, timestamp, sequence])` → `ctx.serialize(...)`, т.е. буквально «encodes as CLVM», не JSON и не bincode);
2. в **том же бандле** независимая монета-«консьюмер» (свежий `sim.bls(1)`, никак иначе не связанный с оракулом) тратит сама себя и добавляет `ASSERT_PUZZLE_ANNOUNCEMENT` с `announcement_id(singleton_puzzle_hash, message)` — т.е. подтверждено, что сторонний контракт может полагаться на анонс оракула, зная только его полный puzzle hash и формат сообщения, без какой-либо иной связи с монетой оракула.

**Измерение CLVM-стоимости** — отдельная находка, вынесенная в `chain/sim-spike/src/lib.rs::measure_bundle_cost` (см. §9).

Результат (реальные числа симулятора, каждый раунд — 2 монеты в бандле: синглтон + консьюмер):

| Раунд | Cost спенда синглтона (только CLVM-исполнение) | Cost всего бандла (итого) | из них: byte cost | execution cost (2 спенда) | condition cost |
|---|---|---|---|---|---|
| 1 | 77 046 | 29 540 378 | 23 436 000 | 104 378 | 6 000 000 |
| 2 | 107 894 | 29 979 226 | 23 844 000 | 135 226 | 6 000 000 |
| 3 | 107 894 | 29 979 226 | 23 844 000 | 135 226 | 6 000 000 |

Раунд 1 дешевле раундов 2–3 по чистому CLVM-исполнению синглтона (77 046 против 107 894) — из-за EveProof на первом спенде и разной длины CLVM-представления чисел цены/времени по раундам (не проверялось детально, не требовалось задачей); байтовая стоимость (`byte_cost`) и её рост между раундом 1 и 2–3 отражают то же самое — размер генератора бандла зависит от длины сериализованных puzzle reveal/solution.

`condition_cost` стабилен на 6 000 000 во всех трёх раундах, и вот откуда именно берётся эта цифра (проверено по исходнику запиненной версии `chia-consensus = "=0.36.1"`, `~/.cargo/registry/src/index.crates.io-1949cf8c6b5b557f/chia-consensus-0.36.1`): у бандла 4 условия — 2× `CREATE_COIN` (self-recreate синглтона + выход консьюмера) и 2× `AGG_SIG_ME` (по одному на каждый из двух p2-спендов, `manager_p2`/`consumer_p2` — их добавляет сам стандартный p2-паззл для авторизации подписью, а не код теста), плюс по одному announcement-условию на каждой стороне (`CREATE_PUZZLE_ANNOUNCEMENT` у синглтона, `ASSERT_PUZZLE_ANNOUNCEMENT` у консьюмера). Конкретно за штуку берут только `CREATE_COIN` и `AGG_SIG_*` — их стоимость прибавляется в `chia_consensus::conditions::parse_conditions` (файл `src/conditions.rs`, ветки `CREATE_COIN => ... ret.condition_cost += CREATE_COIN_COST` и `AGG_SIG_UNSAFE | AGG_SIG_ME | ... => ... ret.condition_cost += AGG_SIG_COST`, ~строки 1038–1062), а сами константы объявлены в `src/opcodes.rs`: `CREATE_COIN_COST: Cost = 1_800_000` (строка 65), `AGG_SIG_COST: Cost = 1_200_000` (строка 66). Отсюда 2 × 1 800 000 + 2 × 1 200 000 = 6 000 000. Announcement-условия (`CREATE_PUZZLE_ANNOUNCEMENT`/`ASSERT_PUZZLE_ANNOUNCEMENT`) в эту сумму не входят и стоят 0: общая надбавка `GENERIC_CONDITION_COST` за «обычное» условие включается только сверх первых `FREE_CONDITIONS = 100` условий бандла (`src/opcodes.rs`, строка 69; логика — там же в `parse_conditions`, счётчик `free_condition_countdown`), а в этом бандле условий на порядок меньше 100, так что все они бесплатны. Итог не зависит от данных раунда (цены/времени/sequence) — поэтому стабилен на всех трёх раундах.

**Негативный тест**: `oracle_singleton_consumer_rejects_wrong_announcement` (тот же файл) — та же схема «синглтон + независимый консьюмер в одном бандле», но консьюмер утверждает (`ASSERT_PUZZLE_ANNOUNCEMENT`) заведомо **другое** сообщение, чем то, которое в этом же бандле реально анонсирует синглтон (сообщение отличается по всем трём полям — price/timestamp/sequence, а значит и по `announcement_id`). `sim.spend_coins(...)` в этом случае обязана вернуть `Err`, и возвращает: `SimulatorError::Validation(ErrorCode::AssertPuzzleAnnouncementFailed)` — Simulator использует ту же консенсус-валидацию, что и полная нода (см. §5), и отклоняет бандл, где утверждённый announcement id ни один спенд бандла фактически не породил (тот же результат, что дало бы удаление спенда синглтона из бандла целиком — announcement, на который ссылается консьюмер, просто ничем не подтверждён). Тест зелёный (`cargo test`, 2026-09-25).

## 8. Тест (d) — Offer PLAY ↔ XCH

Файл: [`chain/sim-spike/tests/offer_play_xch.rs`](../../chain/sim-spike/tests/offer_play_xch.rs). **Сделано, не «не хватило усилий»** — SDK поддерживает Offers из Rust в симуляторе.

Использован высокоуровневый «action system» (`chia_sdk_driver::{Spends, Action, Offer, RequestedPayments, AssetInfo, Id, Relation}`), а не ручная сборка settlement-паззлов — паттерн взят из собственного теста SDK `chia-sdk-driver-0.36.0/src/offers/offer.rs::test_offer_nft_for_nft` (NFT↔NFT), адаптирован под CAT-предложение и XCH-запрос:
- Алиса: `spends.add(play_cat)` → `Action::send(Id::Existing(asset_id), SETTLEMENT_PAYMENT_HASH, PLAY_SUPPLY, ...)` (весь баланс CAT — в settlement-монету) + `requested_payments.xch.push(NotarizedPayment::new(Offer::nonce([play_cat.coin_id()]), [Payment::new(alice_ph, 100, hint)]))` → `spends.conditions.required.extend(requested_payments.assertions(...))` → `Offer::from_input_spend_bundle(...)`.
- Боб: `spends.add(offer.offered_coins().clone())` (settlement-монета с CAT) + `spends.add(bob.coin)` (его собственные XCH) → `spends.apply(&mut ctx, &offer.requested_payments().actions())` (это `Action::settle(Id::Xch, notarized_payment)` — SDK **сам** нашёл в `bob.coin` источник и создал промежуточную settlement-монету на нужную сумму, через `FungibleSpends::intermediate_settlement_source`, без ручного вмешательства) → `offer.take(bob_bundle)` → `sim.new_transaction(...)`.

Результат: `1000 PLAY -> bob, 100 mojo -> alice`, обе итоговые монеты подтверждены в состоянии симулятора.

**Найденная и исправленная ошибка (не баг SDK, ошибка теста)**: на первой попытке монета, финансирующая CAT-эмиссию, была `sim.bls(1)` при объёме эмиссии `PLAY_SUPPLY = 1000` — по той же причине, что в §6 (фандинг-монета должна совпадать по номиналу с объёмом эмиссии 1:1), это дало `ErrorCode::MintingCoin`. Исправлено на `sim.bls(PLAY_SUPPLY)`. Стоило отдельного прохода бисекции (`println!` после каждого шага) — ошибка проявлялась в первой же строке кода, до печати даже первого диагностического сообщения, что подтвердило: проблема в самом первом `sim.spend_coins` (эмиссия), а не в offer-механике.

## 9. Как SDK/`chia-consensus` раскрывают CLVM-cost — метод

**Симулятор cost не отдаёт.** `chia_sdk_test::Simulator::spend_coins`/`new_transaction` возвращают только `IndexMap<Bytes32, CoinState>` (обновлённые состояния монет) — ни исполненный cost, ни его разбивку они не публикуют. Копия алгоритма, которую `chia-sdk-test` использует у себя внутри (`validate_clvm_and_signature`/`run_spendbundle` в `chia-sdk-test-0.36.0/src/validate_clvm_and_signature.rs`), помечена `pub(crate)` — физически недоступна из зависимого крейта вроде нашего `sim-spike`.

**Переписывать алгоритм не понадобилось** — у `chia_consensus` (запиненная версия `=0.36.1`) есть **публичная функция верхнего уровня** с той же сигнатурой и логикой: `chia_consensus::spendbundle_conditions::run_spendbundle` (`pub fn`, файл `chia-consensus-0.36.1/src/spendbundle_conditions.rs`, строка 46; модуль `spendbundle_conditions` объявлен `pub mod` в `src/lib.rs`, строка 27 — проверено чтением исходника после `cargo build`, 2026-09-25). Это именно тот код, который `chia-sdk-test` копирует к себе (тот же построчный алгоритм, тот же комментарий «adapted from `run_block_generators::run_block_generator2()`») — копия там нужна только для доступа к альтернативному CLVM-диалекту для отладки Rue-паззлов под WASM (see `chia-sdk-test-0.36.0/src/validate_clvm_and_signature.rs`, комментарий «copied here because the upstream doesn't support custom dialects»), не потому что публичной версии не существует.

`chain/sim-spike/src/lib.rs::measure_bundle_cost` строит `chia_protocol::SpendBundle` из переданных `CoinSpend` (агрегированная подпись — placeholder, `run_spendbundle` её не читает) и вызывает `chia_consensus::spendbundle_conditions::run_spendbundle(&mut allocator, &spend_bundle, SIMULATOR_MAX_COST, ENABLE_KECCAK_OPS_OUTSIDE_GUARD, &TESTNET11_CONSTANTS)` — те же флаги/константы/лимит cost, что `Simulator::new_transaction` использует внутри (`chia-sdk-test-0.36.0/src/simulator.rs`). Возвращённая `SpendBundleConditions` уже содержит `cost`/`execution_cost`/`condition_cost` как отдельные поля; `byte_cost` — их разность (`cost - execution_cost - condition_cost`, ровно так эти три числа документированы в самой структуре, `chia-consensus-0.36.1/src/conditions.rs`, комментарии у полей `cost`/`execution_cost`/`condition_cost`).

Дополнительный плюс вызова публичной функции вместо ручного порта: `run_spendbundle` по пути проверяет `coin_spend.coin.puzzle_hash == tree_hash(puzzle_reveal)` и возвращает `ErrorCode::WrongPuzzleHash`, если это не так (`chia-consensus-0.36.1/src/spendbundle_conditions.rs`, строки 78–81) — та же проверка, что сделала бы настоящая консенсус-валидация; ручной построчный порт, который был в этом файле раньше, эту проверку пропускал (что не проявлялось на всех «положительных» тестах этого спайка, поскольку puzzle hash там всегда корректен, но означало более слабую гарантию, чем даёт `Simulator`).

`chia_sdk_types::run_puzzle_with_cost` (тонкая обёртка над `clvmr::run_program`, возвращает `Reduction(cost, output)`) по-прежнему используется — но не в `lib.rs`, а напрямую в `oracle_singleton.rs`, для стоимости исполнения одного паззла в отдельности (стоимость спенда синглтона «самого по себе», в таблице §7 колонка «Cost спенда синглтона»).

Поля `ConsensusConstants` (например `cost_per_byte`) в исходнике объявлены без `pub`, но макрос `#[chia_streamable_macro::streamable]` переписывает видимость каждого поля на `pub` во время компиляции (проверено чтением исходника самого макроса, `chia_streamable_macro-0.36.1/src/lib.rs`, строка `field.vis = Visibility::Public(Pub::default())`) — так что `TESTNET11_CONSTANTS.cost_per_byte` доступен снаружи, несмотря на то что в файле `consensus_constants.rs` это не видно текстом.

## 10. RewardDistributor / Action Layer (CHIP-50/51) — найдено, не реализовывалось

Как просила задача — только отчёт, реализации нет.

- **`RewardDistributorType::Managed`** существует буквально: `chia_sdk_driver::primitives::action_layer::reward_distributor_info::RewardDistributorType` — enum с 4 вариантами: `Managed { manager_singleton_launcher_id: Bytes32 }`, `NftCollection { collection_did_launcher_id }`, `CuratedNft { store_launcher_id, refreshable }`, `Cat { asset_id, hidden_puzzle_hash }`. `Managed` — именно тот паттерн, на который ссылается `the project plan` §3: резолвер авторизуется синглтоном-менеджером по его `launcher_id`, а не хардкоженным ключом.
- **Основной примитив**: `chia_sdk_driver::primitives::action_layer::reward_distributor::RewardDistributor` (850 строк) — конструкторы `from_spend`, `from_parent_spend`, `from_launcher_solution`, `from_eve_coin_spend`, `from_mempool_item`; метод завершения спенда `finish_spend`; генерик `new_action<A>()` для добавления action-спендов.
- **Запуск**: `chia_sdk_driver::primitives::action_layer::launch_drivers::launch_reward_distributor` (свободная функция, файл `launch_drivers.rs`, 5149 строк — самый большой файл в крейте, там же `launch_catalog_registry`, `launch_xchandles_registry` и вспомогательные `spend_settlement_*`/`spend_security_coin`).
- **Отдельные action-спенды** (по одному файлу на действие) — `chia_sdk_driver::layers::action_layer::actions::reward_distributor::{stake, unstake, sync, new_epoch, commit_incentives, add_incentives, withdraw_incentives, initiate_payout, add_entry, remove_entry, refresh, action_log}`.
- **Фича-флаг**: всё это гейтится `action-layer` (и транзитивно `chip-0035`) в `chia-wallet-sdk`/`chia-sdk-driver`/`chia-sdk-types` — без него код не виден. В `chain/sim-spike/Cargo.toml` фича уже включена (нужна была и для этого поиска, и на будущее).
- Отдельно существует более простой родственный примитив `state_scheduler`/`state_scheduler_info` (`primitives/action_layer/state_scheduler*.rs`) — синглтон, обновляющий состояние по расписанию под управлением одного ключа; концептуально ближе к «просто оракулу», чем полный `RewardDistributor`, но не исследовался глубже (не требовалось задачей).

Вывод для недели 2–3 (`the project plan` §4): готового `create_market/resolve/redeem` action-layer приложения нет нигде (подтверждено ещё в `c2_deep_review.md` §3c), но `RewardDistributor`/`launch_reward_distributor` и его action-файлы — прямой референс по объёму и структуре кода, на который можно ориентироваться при написании своего action-layer приложения (тот самый «референс `Yakuhito/slot-machine`» из `the project plan` §3, если `chia-wallet-sdk` и есть его переиздание/форк с той же командой — это не проверялось в этом проходе).

## 11. Итог по открытому вопросу §8.1 (полная нода vs подключение к пирам)

**Не закрыт этим спайком, и не должен был** — симулятор `chia-sdk-test::Simulator` полностью in-memory, не использует ни полную ноду, ни соединение с пирами вообще (в этом и весь смысл офлайн-тестирования). Вопрос «нужна ли полная нода для спенда кастомного синглтона» относится к **testnet11**-части (день 2–3 по `the project plan` §5), не к дню 1. `chia-sdk-client` (пиринг/RPC) запинен в `Cargo.toml` по прямому указанию задачи, но не используется ни в одном тесте — оставлен как задел на день 2–3.

## 12. Блокеры / открытые вопросы дальше недели 1, дня 1

Явных блокеров для продолжения нет — все пункты (a)–(e) выполнены. Незакрытое на будущее:
- `chain/sim-spike/src/lib.rs::measure_bundle_cost` — тонкая обёртка над публичной `chia_consensus::spendbundle_conditions::run_spendbundle` (см. §9), не самодельный порт; отдельных юнит-тестов на неё саму нет, но она напрямую вызывается тестом (c) (`oracle_singleton_three_price_updates`, каждый раунд). Негативный тест `oracle_singleton_consumer_rejects_wrong_announcement` её не вызывает — он проверяет отдельный путь, ту же самую консенсус-логику внутри `Simulator::spend_coins` (см. §7). Если `measure_bundle_cost` будут переиспользовать в день 3 для реального спенда оракула на testnet11, стоит свериться с реальным `mempool`-fee-estimate ноды на testnet11, а не доверять только симулятору.
- Из «action-layer»-находок §10 не проверялось: совместим ли `RewardDistributor`/`launch_reward_distributor` именно с офлайн-симулятором (тесты внутри `chia-sdk-driver` для reward_distributor не просматривались построчно — только структура файлов и сигнатуры).
