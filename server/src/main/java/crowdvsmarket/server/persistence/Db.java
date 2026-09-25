package crowdvsmarket.server.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.function.Function;
import javax.sql.DataSource;
import org.postgresql.util.PGobject;

/**
 * Small JDBC helper (предложение: plain JDBC over Agroal's {@link DataSource}, not an ORM — the
 * schema is event-log-plus-read-models, not an object graph, so hand-written SQL stays simpler
 * than mapping it through Hibernate/Panache). Every mutating call in this codebase manages its own
 * transaction explicitly via {@link #tx}, rather than relying on JTA/{@code @Transactional}.
 */
@ApplicationScoped
public class Db {

    @Inject
    DataSource dataSource;

    @Inject
    ObjectMapper objectMapper;

    public ObjectMapper json() {
        return objectMapper;
    }

    /** Runs {@code work} inside one JDBC transaction (manual commit/rollback); returns its result. */
    public <T> T tx(SqlWork<T> work) {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                T result = work.run(c);
                c.commit();
                return result;
            } catch (Exception e) {
                c.rollback();
                throw sneaky(e);
            }
        } catch (SQLException e) {
            throw new RuntimeException("db connection failure", e);
        }
    }

    /** A plain, connection-scoped (implicit auto-commit) read; no transaction needed. */
    public <T> T read(SqlWork<T> work) {
        try (Connection c = dataSource.getConnection()) {
            return work.run(c);
        } catch (Exception e) {
            throw sneaky(e);
        }
    }

    @FunctionalInterface
    public interface SqlWork<T> {
        T run(Connection c) throws SQLException;
    }

    @FunctionalInterface
    public interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    public <T> T queryOne(Connection c, String sql, RowMapper<T> mapper, Object... params) throws SQLException {
        try (PreparedStatement ps = prepare(c, sql, params); ResultSet rs = ps.executeQuery()) {
            return rs.next() ? mapper.map(rs) : null;
        }
    }

    public <T> java.util.List<T> queryList(Connection c, String sql, RowMapper<T> mapper, Object... params)
            throws SQLException {
        java.util.List<T> out = new java.util.ArrayList<>();
        try (PreparedStatement ps = prepare(c, sql, params); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(mapper.map(rs));
            }
        }
        return out;
    }

    public int update(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = prepare(c, sql, params)) {
            return ps.executeUpdate();
        }
    }

    private PreparedStatement prepare(Connection c, String sql, Object[] params) throws SQLException {
        PreparedStatement ps = c.prepareStatement(sql);
        for (int i = 0; i < params.length; i++) {
            Object p = params[i];
            if (p instanceof Jsonb j) {
                PGobject pg = new PGobject();
                pg.setType("jsonb");
                try {
                    pg.setValue(j.json == null ? null : objectMapper.writeValueAsString(j.json));
                } catch (Exception e) {
                    throw new SQLException("failed to serialize jsonb param", e);
                }
                ps.setObject(i + 1, pg);
            } else if (p == null) {
                ps.setNull(i + 1, Types.OTHER);
            } else {
                ps.setObject(i + 1, p);
            }
        }
        return ps;
    }

    /** Wrap an object to be written into a {@code jsonb} column. */
    public static Jsonb jsonb(Object value) {
        return new Jsonb(value);
    }

    public record Jsonb(Object json) {
    }

    /** Reads a {@code jsonb}/{@code json} column back into {@code type} via Jackson. */
    public <T> T readJson(ResultSet rs, String column, Class<T> type) throws SQLException {
        String raw = rs.getString(column);
        if (raw == null) {
            return null;
        }
        try {
            return objectMapper.readValue(raw, type);
        } catch (Exception e) {
            throw new SQLException("failed to deserialize jsonb column " + column, e);
        }
    }

    public <T> T readJson(ResultSet rs, String column, Function<String, T> parser) throws SQLException {
        String raw = rs.getString(column);
        return raw == null ? null : parser.apply(raw);
    }

    public <T> T readJson(ResultSet rs, String column, com.fasterxml.jackson.core.type.TypeReference<T> type)
            throws SQLException {
        String raw = rs.getString(column);
        if (raw == null) {
            return null;
        }
        try {
            return objectMapper.readValue(raw, type);
        } catch (Exception e) {
            throw new SQLException("failed to deserialize jsonb column " + column, e);
        }
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> RuntimeException sneaky(Throwable t) throws E {
        throw (E) t;
    }
}
