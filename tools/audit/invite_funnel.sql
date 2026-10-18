-- Read-only. Players registered in a period, by acquisition source and by inviter (bots excluded).
-- Usage: psql -v from_ms=<epoch ms> -v to_ms=<epoch ms> -f tools/audit/invite_funnel.sql
-- (created_at_ms is the registration time; to_ms is exclusive.)
-- players.source     channel tag of a link we posted (?src=discord ...), NULL when the visitor came without one
-- players.referred_by  player_id of the inviter (?ref=<code>), NULL when none; both written once, at registration (V15).
-- "placed_bet" = the player has at least one BetPlaced event in game_events.

-- 1. One row per source / referral kind: how many came, how many placed a bet.
SELECT COALESCE(p.source, '(none)')                                  AS source,
       CASE WHEN p.referred_by IS NULL THEN 'no ref' ELSE 'ref' END  AS via_player_link,
       COUNT(*)                                                      AS registered,
       COUNT(*) FILTER (WHERE EXISTS (
           SELECT 1 FROM game_events e
           WHERE e.event_type = 'BetPlaced' AND e.payload ->> 'playerId' = p.player_id)) AS placed_bet
FROM players p
WHERE NOT p.is_bot
  AND p.created_at_ms >= :from_ms AND p.created_at_ms < :to_ms
GROUP BY 1, 2
ORDER BY registered DESC;

-- 2. The list: who registered, from where, through whom (inviter shown by nickname, not by id).
SELECT to_timestamp(p.created_at_ms / 1000.0) AT TIME ZONE 'UTC' AS registered_utc,
       p.player_id,
       COALESCE(p.source, '(none)')                              AS source,
       COALESCE(r.nickname, r.player_id, '(none)')               AS invited_by
FROM players p
LEFT JOIN players r ON r.player_id = p.referred_by
WHERE NOT p.is_bot
  AND p.created_at_ms >= :from_ms AND p.created_at_ms < :to_ms
ORDER BY p.created_at_ms;

-- 3. Inviters: how many each brought (the counter the player sees).
SELECT COALESCE(r.nickname, r.player_id) AS inviter, COUNT(*) AS brought
FROM players p
JOIN players r ON r.player_id = p.referred_by
WHERE NOT p.is_bot
  AND p.created_at_ms >= :from_ms AND p.created_at_ms < :to_ms
GROUP BY 1
ORDER BY brought DESC;
