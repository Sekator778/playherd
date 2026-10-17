# tools/economy/scale

Скрипты расчёта `docs/research/economy_at_scale_math_2026-10-16.md` (автор — агент-математик, 2026-10-16) и скрипты независимой проверки `docs/research/economy_at_scale_audit_2026-10-17.md`.

Происхождение: скрипты скопированы без изменения логики из scratchpad сессии 2026-10-16 (`common.py`, `s1_stats_capacity.py`, `s2lib.py`, `s2_risk_mc.py`, `s3_safe_lambda.py`, `s3b_informed.py`, `s3c_ruin.py`, `s4_pool_skew.py`, `s5_tables.py`, `s6_round.py`). Единственная правка: путь к свечам в `common.py` был абсолютным, теперь это переменная окружения `CANDLES_DIR` (по умолчанию `.local/bots-candles`).

## Данные

Свечи 1 минута, гитигнорированы: `.local/bots-candles/{BTC,ETH,SOL,NVDA,GOLD}_1m.csv` (Coinbase BTC/ETH/SOL, 14 суток; Hyperliquid xyz NVDA/GOLD, 3,5 суток). Формат строки: `ts_ms,open,high,low,close`; строки с `#` — комментарии.

## Запуск

Нужны Python 3.14 (подойдёт 3.11+), numpy, scipy (scipy нужен `s2lib.py` для `maximum_filter1d`).

```
python3 -m venv /tmp/venv-scale && /tmp/venv-scale/bin/pip install numpy scipy
mkdir /tmp/scale-run && cp tools/economy/scale/*.py /tmp/scale-run/ && cd /tmp/scale-run   # рабочий каталог вне репозитория: s1 пишет pools.npz в текущий каталог
export CANDLES_DIR=/абсолютный/путь/к/.local/bots-candles
PYTHONPATH=. /tmp/venv-scale/bin/python s1_stats_capacity.py > out_s1.txt   # СНАЧАЛА: пишет pools.npz, нужный s2lib
PYTHONPATH=. /tmp/venv-scale/bin/python s6_round.py       > out_s6.txt
PYTHONPATH=. /tmp/venv-scale/bin/python s4_pool_skew.py   > out_s4.txt
PYTHONPATH=. /tmp/venv-scale/bin/python s5_tables.py      > out_s5.txt
PYTHONPATH=. /tmp/venv-scale/bin/python s2_risk_mc.py     > out_s2.txt   # самый долгий (минуты)
PYTHONPATH=. /tmp/venv-scale/bin/python s3_safe_lambda.py > out_s3.txt
PYTHONPATH=. /tmp/venv-scale/bin/python s3b_informed.py   > out_s3b.txt
PYTHONPATH=. /tmp/venv-scale/bin/python s3c_ruin.py       > out_s3c.txt
```

Флаг `-I` для интерпретатора здесь неприменим: он отключает и каталог скрипта, и `PYTHONPATH`, и соседние модули (`common`, `s2lib`) не импортируются. Использовалось `python -E -s`.

Результат воспроизводится бит-в-бит: генераторы случайных чисел с фиксированным seed; перезапуск 2026-10-17 дал выводы, совпавшие с `out_s*.txt` исходного прогона (в `s2`, `s3*`, `s6` отличались только две строки заголовка, которые исходный вывод не сохранил).

## audit/

Скрипты аудита (запускать из каталога `audit/`, `CANDLES_DIR` как выше):

- `audit_bb.py` — блочный бутстрэп (блоки 15 мин … 1 сутки, общий минутный индекс для потоков 3 и 15 мин) и стохастическая волатильность поверх; `python audit_bb.py <блок_в_минутах> [sv <sd>]`.
- `audit_independent_checks.py` — независимый расчёт σ шага/суток, утечки ℓ, сценарий «одна сторона на все 12 рынков», гауссов контроль P(пробой), чувствительность к множителю σ.
- `audit_block_bootstrap_365.py` — P(пробой за 365 суток) при λ = 0,03…0,1, блок 1 сутки (+ SV 0,7).
- `audit_arithmetic.py` — вывод (b) через комиссии и утечку, пример нелинейности при высоком плече, K0-порог при T = T0 − K0, округление комиссий при q = 100, таблица «1 млн игроков».
