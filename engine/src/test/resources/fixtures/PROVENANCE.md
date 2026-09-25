# PROVENANCE — фикстуры BTC-свечей Hyperliquid

Файлы в этой директории (`hl_btc_1d.csv`, `hl_btc_1h.csv`) получены скриптом
[`tools/fetch_hl_candles.py`](../../../../../tools/fetch_hl_candles.py) (python3, только stdlib/urllib,
без ключей, без записи — публичный read-only `/info`-эндпоинт Hyperliquid).

## Эндпоинт и запрос

```
POST https://api.hyperliquid.xyz/info
Content-Type: application/json

{"type":"candleSnapshot","req":{"coin":"BTC","interval":"1d","startTime":<ms>,"endTime":<ms>}}
{"type":"candleSnapshot","req":{"coin":"BTC","interval":"1h","startTime":<ms>,"endTime":<ms>}}
```

Скрипт пагинирует вперёд от `startTime=0`, продвигая курсор до `now`, и де-дублирует по
`open_time_ms` — см. docstring `tools/fetch_hl_candles.py`.

Документация эндпоинта и cap на число свечей в ответе (дословно: «Only the most recent
5000 candles are available»):
https://hyperliquid.gitbook.io/hyperliquid-docs/for-developers/api/info-endpoint.md
(раздел «Candle snapshot»), fetched 2026-09-25.

Практически замеренный (не документированный явно) эффект этого cap на 2026-09-25:

- `1d` — вся история укладывается в одну страницу (2228 строк < 5000), cap не сработал.
- `1h` — доступны только последние ~5000 часов; запросы на более раннее окно
  (проверено вручную на нескольких датах между 2020 и 2026-02) возвращают 0 свечей.
  Самая ранняя доступная `1h`-свеча на момент фетча — `2026-03-01T04:00:00Z`.

## Фетч (эта сессия)

- Дата/время фетча (UTC): 2026-09-25, ~14:12 UTC (`date -u` перед запуском скрипта).
- Команда: `python3 tools/fetch_hl_candles.py` (дефолтные `--coin BTC --intervals 1d,1h`).
- Текущая (незакрытая) свеча каждого интервала исключена: свеча включается в CSV только
  если `close_time_ms < now_ms` на момент запроса.

## Файлы

### `hl_btc_1d.csv`

- Строк данных: **2228** (плюс строка заголовка).
- Первая свеча: `open_time_ms=1597795200000` → `2020-08-19T00:00:00Z`.
- Последняя свеча: `open_time_ms=1790208000000` → `2026-09-24T00:00:00Z` (последний полностью
  закрытый день; свеча текущего дня `2026-09-25` исключена как незакрытая).
- SHA-256: `0a5c9aa3132a4821b60e939a0aab6f2357e47c47ffeee7bb040dba47bb3e370c`
- Замечание по данным: самые ранние свечи (2020-08-19 и далее, до реального запуска
  торгов BTC-перпа на Hyperliquid) имеют `volume=0.0, trades=0` — это backfill
  индексной/оракульной цены, а не реальный объём торгов на Hyperliquid. API отдаёт эти
  значения как есть; мы не отфильтровываем и не помечаем их отдельно в CSV — движок,
  использующий эту фикстуру для сценариев ликвидации, работает по OHLC независимо от
  объёма/числа сделок.

### `hl_btc_1h.csv`

- Строк данных: **5002** (плюс строка заголовка).
- Первая свеча: `open_time_ms=1772337600000` → `2026-03-01T04:00:00Z` (самая ранняя
  доступная через `candleSnapshot` на момент фетча, см. выше).
- Последняя свеча: `open_time_ms=1790341200000` → `2026-09-25T13:00:00Z` (последний
  полностью закрытый час; свеча `14:00Z`, шедшая на момент фетча, исключена).
- SHA-256: `609df94a12349dc1602ae0af8c094aaaefb204613803f0ded2b5204d13909fa3`

## Формат CSV

Заголовок: `open_time_ms,close_time_ms,open,high,low,close,volume,trades`

- `open_time_ms`/`close_time_ms` — поля `t`/`T` ответа API (epoch ms).
- `open,high,low,close,volume` — поля `o,h,l,c,v` ответа API, записаны как есть (те же
  десятичные строки, без округления/парсинга через float).
- `trades` — поле `n` ответа API (число сделок в свече).
- Сортировка: по возрастанию `open_time_ms`. Дедупликация: по `open_time_ms`.

## Источники

- Доки эндпоинта и cap: https://hyperliquid.gitbook.io/hyperliquid-docs/for-developers/api/info-endpoint.md, fetched 2026-09-25.
- Полный контекст модели, использующей эти фикстуры: [`docs/research/hl_liquidation_math.md`](../../../../../docs/research/hl_liquidation_math.md).
