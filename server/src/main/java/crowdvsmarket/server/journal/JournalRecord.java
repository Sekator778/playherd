package crowdvsmarket.server.journal;

/**
 * One event of the journal as it is stored: the columns of {@code game_events} (Р-107). {@code payload} is the event's JSON as the
 * database stores it; the journal never looks into it.
 *
 * @param seq           the place of the event in the journal ({@code game_events.id}); strictly increasing, gaps allowed (a journal imported
 *                      from the table keeps the ids the table had)
 * @param type          {@code game_events.event_type}
 * @param schemaVersion {@code game_events.schema_version}
 * @param atMs          {@code game_events.at_ms}
 * @param market        {@code game_events.market}, or {@code null}
 * @param playerId      {@code game_events.player_id}, or {@code null}
 */
public record JournalRecord(long seq, String type, int schemaVersion, long atMs, String market, String playerId, String payload) {
}
