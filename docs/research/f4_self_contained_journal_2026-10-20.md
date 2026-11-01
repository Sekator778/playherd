# F4: журнал самодостаточен, применение события чистое

Дата: 2026-10-20. Основание: Р-96 (`the decision log`, шаг 2 «фундамент без смены поведения»), `docs/research/architecture_review_independent_2026-10-16.md` §4.1 строка F4, `docs/research/lmax_roadmap.md` §6. Ветка `feature/arch-f4-journal`.

Правила текста: утверждение — со ссылкой на файл; **[вывод]** — заключение с тем, на чём оно стоит; «неизвестно» — не проверено.

## 1. Что изменилось

| Пункт F4 | Где |
|---|---|
| `PlayerRegistered` несёт строку игрока: кошелёк, индекс, имя, вид (`HUMAN` / `HOUSE_BOT` / `API_BOT`), стратегию дом-бота, владельца API-бота, «заменяет такого-то» | `events/GameEvent.java` (`PlayerRegistered.registration`) |
| Строка `players` — проекция: пишется в транзакции события, чинится при перестройке | `GameService.registerOrTouchPlayer`, `createBotPlayer`, `createApiBot`; `PlayerDirectory.project`, `PlayerDirectory.repairFromJournal` |
| Дальнейшие изменения строки — события: `NicknameChanged`, `BotStrategyAssigned`, `BotRetired` (уже был) | `GameService.setNickname`, `renameBot`, `assignBotStrategy`, `retireBotsBelowThreshold` |
| `MarketListed` / `MarketClosed`; таблица `markets` — проекция | `markets/DbMarketRegistry.java` (`insertNext`, `closeMarket` пишут событие в той же транзакции; `projectFromJournal` чинит) |
| Номер оракула назначает движок и несёт `RoundResolved.oracleSeq` | `GameService.journalAndApplyLocked` (счётчик `EngineHub.oracleSeq`, пол от `OracleSeqGate` через `ServerMetaRepository.raisedOracleFloor`); запись оракула берёт номер из события (`enqueueOracleRecord`); `server_meta.oracle_seq` следует за журналом (`projectOracleSeq`) |
| `EventApplier` ничего не читает вне хаба | убран `PlayerDirectory`; имена и признак бота для ленты ставок берутся из `EngineHub.roster()` (`domain/PlayerRoster.java`) |
| `CURRENT_SCHEMA_VERSION` = 2 с веткой декодирования | `events/EventStore.java` (`decode`) |

## 2. Версия 1 и версия 2

- Строка версии 1 (всё, что писал сервер до F4) читается теми же записями: новые компоненты отсутствуют и равны `null`. `PlayerRegistered` без кошелька — выдача пакета (как раньше), `RoundResolved` без `oracleSeq` берёт номер из `server_meta` при записи строк (как раньше).
- Типы, рождённые в версии 2 (`NicknameChanged`, `BotStrategyAssigned`, `MarketListed`, `MarketClosed`), в строке версии 1 — ошибка с номером строки.
- Версии, кроме 1 и 2, отвергаются как раньше.
- Новые события пишутся версией 2. Событие возвращающегося игрока (только id) по-прежнему пишется теми же байтами, что и в версии 1.
- Игроки версии 1 в журнале не описаны: перестройка кладёт в `roster` строки таблицы `players` до реплея, событие версии 2 их перекрывает.
- Рынки версии 1 в журнале не описаны: таблица `markets` остаётся источником; BTC — строка миграции (`V7__markets.sql`), событием не листится.

## 3. Что остаётся вне журнала намеренно

- Ключ API-бота (хеш и подсказка) — только в таблице `players`. Реплей в пустую базу даёт бота без ключа.
- Колонки `invite_code`, `referred_by`, `source` игрока.
- Таблицы вывода и ввода, сессии, лента обратной связи.

## 4. Проверки

- `JournalSelfContainedTest`: журнал, применённый одним `EventApplier` в пустой хаб, даёт тот же дайджест состояния движка; после стирания `players`, `markets` и двух счётчиков перестройка воспроизводит дайджест целиком (движок, обе таблицы, счётчики).
- `JournalVersion1ReplayTest`: журнал, снятый кодом до F4 (фикстура `server/src/test/resources/events/journal-v1/`), реплеится в тот же дайджест движка, что был у старого сервера.
- `testsupport/StateDigest.java`: канонический текст (отсортированные разделы) и SHA-256.
- Золотые фикстуры типов (`EventStoreGoldenFixturesTest`) для четырёх новых типов; версия записи — 2.

## 5. Открытое

- **[вывод]** Лента ставок после перестройки показывает имя игрока версии 2 таким, каким оно было в момент ставки, а не последним (раньше — всегда последним из таблицы). На игроков версии 1 это не влияет.
- Реплей 265 000+ событий живого стенда этой веткой не проверялся: неизвестно. Проверено на журнале из фикстуры.
- Проекции чинятся при каждой перестройке полным проходом по списку событий (память как у `loadAll`); потоковое чтение — F5.
