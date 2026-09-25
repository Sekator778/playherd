# Неделя 1, день 1-2 — `c2`: Rust CLI для testnet11 (chain/c2-cli)

Дата — **2026-09-25**. Источники: `docs/research/week1_chain_access.md` (маршрут доступа к сети), `chain/sim-spike/` (доказанные офлайн-паттерны сборки спендов), `docs/product/decisions.md` Р-1…Р-21, `chain/c2-cli/` (код этого прохода — все факты о деривации ключей и формате `offer1...` ниже читаны из исходников зависимостей в `~/.cargo/registry/src/index.crates.io-1949cf8c6b5b557f/`, версии как в `chain/c2-cli/Cargo.toml`).

Код: новый крейт `chain/c2-cli` (бинарник `c2`), добавлен в `chain/Cargo.toml` `[workspace] members`. `chain/sim-spike` не менялся.

---

## 1. Команды

Собрано в `chain/c2-cli/src/main.rs` (`clap`, подкоманды):

| Команда | Что делает | Push |
|---|---|---|
| `c2 keygen --name <n>` | Новый BIP-39-кошелёк, сохраняет ключ, печатает адрес index 0 | — |
| `c2 address --name <n> [--index i]` | Печатает `txch1...`-адрес по индексу (по умолчанию 0) | — |
| `c2 balance --name <n>` | Сумма непотраченных монет по адресам 0..20 (coinset.org, read-only) | — |
| `c2 issue-play --name <n> --amount <mojo> [--fee <mojo>]` | Чеканит PLAY CAT (genesis-by-coin-id, single-issuance) | dry-run / `--push` |
| `c2 offer-make --name <n> --requested-mojos <mojo>` | Оффер «весь закешированный PLAY → TXCH», печатает `offer1...` | никогда (см. §4) |
| `c2 offer-take --name <n> --offer <offer1...>` | Принимает оффер (платит TXCH, получает PLAY) | dry-run / `--push` |
| `c2 oracle-launch --name <n> [--fee <mojo>]` | Запускает синглтон-оракул (Р-11), manager-ключ — этот кошелёк | dry-run / `--push` |
| `c2 oracle-publish --name <n> --price <i64> --ts <i64>` | Пересоздаёт синглтон + announcement (price, ts, sequence) | dry-run / `--push` |

Каждая спендящая команда по умолчанию **dry-run**: строит + подписывает + валидирует локально (`chia_consensus::spendbundle_conditions::run_spendbundle`, тот же алгоритм, что `chia-sdk-test::Simulator` использует перед принятием бандла — см. `chain/c2-cli/src/cost.rs`, портировано из `chain/sim-spike/src/lib.rs`), печатает JSON тела `push_tx` (`chain/c2-cli/src/spend_json.rs`, та же форма, что `chia-sdk-coinset::push_tx` реально отправит) и разбивку CLVM-cost (`total/byte/execution/condition`) + `fee`. Пушит только с явным `--push`. **В этом проходе `--push` нигде не использован** (по прямому указанию задачи).

## 2. Хранение ключей

`~/.c2/testnet11/<name>.json` = `{"mnemonic", "created_utc", "network"}` (ровно 3 поля, как в задаче). Директории `~/.c2` и `~/.c2/testnet11` — `0700`, файл ключа — `0600` (`chain/c2-cli/src/keystore.rs::ensure_key_dirs`/`save_new`, `std::fs::Permissions::from_mode`). `keygen` отказывается перезаписывать существующий файл (`OpenOptions::create_new`, атомарно). Мнемоника никогда не печатается — только `keygen` пишет её в файл; `keygen`/`address`/`balance` печатают только адрес/суммы.

Проверено реальным запуском (см. §6): оба файла созданы с правами `600`, каталоги — `700`; повторный `keygen --name operator` отклонён с `refusing to overwrite`.

Дополнительно, только как **кеш публичных данных цепи** (не секрет, свободно перезаписывается): `~/.c2/testnet11/<name>.play.json` — последняя минченная/полученная PLAY-CAT-монета этого кошелька (asset id, монета, lineage proof) и `~/.c2/testnet11/<name>.oracle.json` — текущее поколение запущенного этим кошельком синглтона-оракула (launcher id, текущая монета/proof, следующий sequence). **Предложение** (решить/пересмотреть при необходимости): вместо построения универсального сканера CAT-монет по цепочке (нужен `get_puzzle_and_solution` родителя + `Cat::parse_children`, `chia-sdk-driver-0.36.0/src/primitives/cat.rs:387`) `offer-make` берёт монету только из этого кеша — т.е. предлагает только то, что сам `issue-play` этим же CLI начеканил или получил. Для недели 1 (только `operator`/`counterparty`, оба под контролем агента) этого достаточно; полноценный сканер — задача дня 2-3, если понадобится оффер из монеты, полученной не через этот CLI.

## 3. Путь деривации ключа (с источниками)

Реализовано в `chain/c2-cli/src/keys.rs` (там же — полный doc-comment с точными путями к файлам):

1. **Мнемоника → seed.** 24-словная BIP-39 (английский), `bip39::Mnemonic::to_seed("")` — обычный BIP-39 (PBKDF2-HMAC-SHA512, соль `"mnemonic"+пароль`, 2048 раундов, пароль `""`). Это ровно то, что делает `chia-blockchain`'овский `mnemonic_to_seed` — Chia не добавляет свой твист на этом шаге.
2. **Seed → мастер-ключ.** `chia_bls::SecretKey::from_seed(seed)` = EIP-2333 `derive_master_SK` (`chia-bls-0.36.1/src/secret_key.rs::from_seed`, doc-comment там ссылается на eips.ethereum.org/EIPS/eip-2333). Это тот же примитив, что оборачивает `AugSchemeMPL.key_gen` в Python-`chia-blockchain`.
3. **Мастер → wallet-ключ по индексу `i`: путь `m/12381/8444/2/i`, ПОЛНОСТЬЮ unhardened.** `chia_bls::master_to_wallet_unhardened(&master_sk, i)` (`chia-bls-0.36.1/src/derive_keys.rs`) — все 4 компонента пути дерайвятся unhardened-функцией (`DerivableKey::derive_unhardened`), не hardened. Это современный «observer»-путь, которым `chia-blockchain` (>=1.7, watch-only-кошельки) и Sage по умолчанию строят адреса стандартного кошелька — в отличие от старого полностью hardened пути (тот же файл, `master_to_wallet_hardened`), который здесь намеренно не используется.
4. **Wallet-ключ → синтетический ключ.** p2-пазл (`p2_delegated_puzzle_or_hidden_puzzle`, обычный Chia-адрес) курируется СИНТЕТИЧЕСКИМ ключом, не wallet-ключом напрямую: `synthetic_sk = wallet_sk.derive_synthetic()` (`chia-puzzle-types-0.36.1/src/derive_synthetic.rs`, трейт `DeriveSynthetic`) — фиксированное BLS-смещение от `sha256(wallet_pk || DEFAULT_HIDDEN_PUZZLE_HASH)`, то же самое, что `calculate_synthetic_secret_key` в `chia-blockchain`. **Каждая подпись спенда этого адреса должна быть сделана `synthetic_sk`**, не `wallet_sk` — подпись «сырым» wallet-ключом не пройдёт `AGG_SIG_ME`-проверку пазла. (Это несложно перепутать — почти у всех тестовых хелперов в `chia-sdk-test` пара `pk/sk` УЖЕ синтетическая, что маскирует этот шаг; см. `chain/c2-cli/src/keys.rs` — там это явно разделено на `wallet_sk` и `synthetic_sk`.)
5. **Puzzle hash / адрес.** `puzzle_hash = StandardArgs::curry_tree_hash(synthetic_pk)` (`chia-puzzle-types-0.36.1/src/puzzles/standard.rs`), `address = bech32m(prefix="txch", puzzle_hash)` (`chia_sdk_utils::Address`, `chia-sdk-utils-0.36.0/src/bech32.rs`). Префикс `txch` — подтверждён живым `get_network_info` в `docs/research/week1_chain_access.md` §1-2.

Юнит-тесты на детерминированность/различие индексов: `chain/c2-cli/src/keys.rs::tests`.

## 4. Формат оффера — `offer1...`, побайтово совместим с эталонным кошельком

`chia_sdk_driver`'s `encode_offer`/`decode_offer` спрятаны за фичей `offer-compression`, которая требует `flate2/zlib-ng-compat` → `libz-sys` (C-библиотека). Порт (`chain/c2-cli/src/offer_compress.rs`) — побайтовая копия `chia-sdk-driver-0.36.0/src/offers/compress.rs` (тот же zlib-словарь из старых пазл-реализаций, тот же 2-байтовый префикс версии, тот же bech32-hrp `"offer"`), но с бэкендом `flate2`'s `zlib-rs` (чистый Rust, поддерживает preset-словарь — в отличие от `flate2`'s дефолтного `miniz_oxide`, у которого словарей нет вообще). Побайтовая идентичность формату проверена реальным тестом против тестовых векторов из исходников `chia-sdk-driver` (`chain/c2-cli/tests/offer_compress_compat.rs` + `tests/test_data/{compressed,decompressed}.offer`, скопированы из `chia-sdk-driver-0.36.0/src/offers/test_data/`) — т.е. `offer1...`-строка этого CLI должна открываться Sage/эталонным кошельком как обычный оффер (не проверялось живым Sage в этом проходе — только байт-в-байт со вшитыми тестовыми векторами апстрима).

`offer-make` никогда не поддерживает `--push` — оффер сам по себе не самодостаточный спенд (запрошенная сторона платежа виртуальна, `parent_coin_info=0`), пушить нечего до `offer-take`. **Предложение:** `--fee` пока не выведен у `offer-make`/`offer-take`/`oracle-publish` (только у `issue-play` и `oracle-launch`, которые прямо тратят финансирующую монету и могут добавить `RESERVE_FEE`+сдачу поверх её условий) — расширить при необходимости на неделе 2.

## 5. Что доказано офлайн (симулятор)

`cargo test --workspace` (после `cargo build`/`test` — см. `Java`/`Maven` в задаче не нужны, это чистый Rust-крейт):

```
export JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-oracle; PATH=$JAVA_HOME/bin:$PATH
cd chain && cargo test --workspace
```

**16 тестов, 16 passed, 0 failed** (без изменений в `sim-spike` — та же команда до этого прохода тоже была зелёной, 5/5):

- `chain/sim-spike` — **5**: `standard_coin`, `cat_play`, `offer_play_xch`, `oracle_singleton` ×2 (не тронуто).
- `chain/c2-cli` — **11**:
  - `src/keys.rs` (2) — деривация детерминирована; разные индексы → разные адреса.
  - `src/keystore.rs` (1) — отказ перезаписи + права `0700`/`0600`.
  - `src/offer_compress.rs` (1) — round-trip на произвольных байтах.
  - `tests/offer_compress_compat.rs` (2) — побайтовая идентичность с тестовыми векторами `chia-sdk-driver` (см. §4).
  - `tests/issue_play.rs` (2) — `issue-play`'овский билдер: чеканка + верная сдача (в симуляторе), отказ при нехватке финансирующей монеты.
  - `tests/offer_play_xch.rs` (1) — полный цикл: `operator` чеканит PLAY → `offer-make` → `offer-take` от `counterparty` → оба получают то, что должны (в симуляторе, через РЕАЛЬНУЮ bech32-строку, не in-memory объект — см. §4).
  - `tests/oracle_singleton.rs` (2) — 3 раунда `oracle-launch`→`oracle-publish`×3 с проверкой announcement сторонним consumer-спендом; отдельный тест на отклонение неверного announcement.

Билдеры (`chain/c2-cli/src/builders/{issue,offer,oracle}.rs`) — это ТЕ ЖЕ функции, которые вызывает боевой путь CLI (`main.rs`), просто вызванные в тестах с монетами из `Simulator::new_coin` вместо монет из `coinset`-ответа — то есть код, который пойдёт в testnet11, действительно прогнан офлайн.

## 6. Реальный прогон на testnet11 сегодня

```
c2 keygen --name operator
c2 keygen --name counterparty
c2 balance --name operator
c2 balance --name counterparty
```

Результат:

- `operator`: `txch1ksxy56805vz64929jj9c44spwzkr5ux96k9lf2e7m39lvczrm5rsgjn24u`, баланс — **0 mojos** (адреса 0..20, coinset.org `get_coin_records_by_puzzle_hash`, `include_spent_coins=false`).
- `counterparty`: `txch15ruuek2a7m6lees94gq5p28ykw05gp47jr4rhs88pj2p6lty6qfq6gqzvg`, баланс — **0 mojos**.
- Ключевые файлы созданы (`~/.c2/testnet11/{operator,counterparty}.json`, `0600`, каталоги `0700`), мнемоники нигде, кроме файлов, не печатались.
- `--push` не использован ни разу; `issue-play`/`offer-*`/`oracle-*` не запускались против реальной цепи (только офлайн в тестах) — оба кошелька пустые, спенд-команды честно откажут с `no unspent coin ... fund the wallet from https://testnet11-faucet.chia.net/ first` (проверено: `c2 issue-play --name operator --amount 10000` вернул именно эту ошибку).

**Факт про сеть при сборке:** `chia-sdk-coinset`'ов `reqwest` собран с `default-features=false` — без TLS-бэкенда вообще, пока потребитель не включит `rustls`/`native-tls` (сам `chia-sdk-coinset-0.36.0/Cargo.toml`). Для реального HTTPS к coinset.org подключена фича `chia-sdk-coinset/rustls`; чтобы её собрать (и `flate2/zlib-rs` для §4), `cargo` один раз обращался к `crates.io`/`static.crates.io` (не к testnet11-инфраструктуре и не к чему-то из правила «исходящие действия») — офлайн-кэш в `~/.cargo/registry` эти крейты не содержал. `~/.m2/settings.xml`-офлайн-guard из общего CLAUDE.md к Cargo не относится (там речь только про Maven); отдельного запрета на сетевой `cargo build` в задаче не было. Если для недели 2 понадобится честный офлайн-билд — оба набора (`rustls`+зависимости, `zlib-rs`) теперь в `~/.cargo/registry` локально и больше сети не потребуют.

## 7. Точные следующие шаги (день 2-3)

1. **Пополнить оба кошелька TXCH.** Вручную, через `https://testnet11-faucet.chia.net/` (капча — форма непригодна для автоматизации, подтверждено `docs/research/week1_chain_access.md` §5; агент площадку не трогал, по прямому запрету этой задачи «never request faucet coins»). Адрес получателя — **Receive Address**, не Master Public Key (`week1_chain_access.md` §5): вставить адреса из §6 (`operator`, `counterparty`) по одному разу каждый.
   - Сколько TXCH нужно технически: `issue-play`/`oracle-launch` чеканят/блокируют мохо 1:1 (CAT-монета — это те же мохо, просто «покрашенные» TAIL'ом, см. комментарий в `chain/c2-cli/src/builders/issue.rs`) — старт-пакет 10 000 PLAY (Р-12) = 10 000 мохо = 0.00000000001 TXCH, плюс произвольная комиссия (`--fee`, тоже в мохо). То есть технический минимум — исчезающе малая доля 1 TXCH; реальное ограничение — что кошелёк вообще не может потратить НИЧЕГО, имея баланс 0, так что каждому кошельку нужна хотя бы одна ненулевая монета. Точный размер выдачи фаусета — **UNVERIFIED** (страница не дергалась, `week1_chain_access.md` тоже это не проверял) — практически любая выдача фаусета этого достаточна с большим запасом.
2. `c2 balance --name operator` / `--name counterparty` — дождаться ненулевого баланса (подтверждение в testnet11, несколько блоков).
3. `c2 issue-play --name operator --amount 10000 --fee <мохо>` — сначала dry-run (проверить JSON/cost/fee), затем повторить с `--push`.
4. `c2 oracle-launch --name operator --fee <мохо>` — dry-run, затем `--push`.
5. `c2 offer-make --name operator --requested-mojos 100` (100 — как минимальная ставка Р-12, для демо-цены) → получить `offer1...`.
6. `c2 offer-take --name counterparty --offer "<offer1...>"` — dry-run (проверить, что `counterparty` получает PLAY, платит запрошенные mojo), затем `--push`.
7. `c2 oracle-publish --name operator --price <цена> --ts <unix-время>` — dry-run, затем `--push`; повторять по кругу для каждого следующего раунда (номер `sequence` CLI ведёт сам в `~/.c2/testnet11/operator.oracle.json`).
8. `c2 balance --name operator` / `--name counterparty` после каждого `--push` — сверить ожидаемое изменение баланса.
