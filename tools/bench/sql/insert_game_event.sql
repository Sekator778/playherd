-- S-F (design §3): one INSERT of one game_events-shaped row per transaction (a BetPlaced of the golden fixture
-- server/src/test/resources/events/golden-economy-b/BetPlaced.json). The commit of this row is the floor of
-- the bet path (one commit under the engine lock = 1/t_commit).
\set at random(1700000000000, 1800000000000)
INSERT INTO bench_game_events (event_type, payload, at_ms, schema_version)
VALUES ('BetPlaced',
        '{"playerId": "p-golden-1", "interval": "HOURLY", "windowStart": 1700000000000, "windowEnd": 1700000180000, "direction": "UP", "leverage": 500, "stakeMojos": 100000, "atMs": 1700000000500, "market": "spot_BTC", "feeHerdMojos": 5000, "feeNetMojos": 100, "liqCap": 1200, "liqKappa": "0.1"}'::jsonb,
        :at, 1);
