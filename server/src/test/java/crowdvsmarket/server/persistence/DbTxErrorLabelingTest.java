package crowdvsmarket.server.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Plain unit test (no {@code @QuarkusTest} — a mocked {@link DataSource}/{@link Connection} is
 * enough), wave-1 finding: {@link Db#tx} used to label *any* {@link SQLException} surfacing from
 * {@code work} (a data problem — bad jsonb value, constraint violation, …) the exact same way as a
 * genuine failure to obtain/use the JDBC connection itself: {@code RuntimeException("db connection
 * failure", …)}. These three cases must now be told apart.
 */
class DbTxErrorLabelingTest {

    private final DataSource dataSource = mock(DataSource.class);
    private final Connection connection = mock(Connection.class);
    private final Db db = new Db();

    @BeforeEach
    void setUp() throws SQLException {
        db.dataSource = dataSource;
        db.objectMapper = new ObjectMapper();
        when(dataSource.getConnection()).thenReturn(connection);
    }

    @Test
    void connectionAcquisitionFailureIsLabeledConnectionFailure() throws SQLException {
        when(dataSource.getConnection()).thenThrow(new SQLException("connection refused"));

        assertThatThrownBy(() -> db.tx(c -> null))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("db connection failure");
    }

    @Test
    void sqlExceptionFromWorkIsLabeledOperationFailedNotConnectionFailure() throws SQLException {
        assertThatThrownBy(() -> db.tx(c -> {
            throw new SQLException("bad jsonb value for column foo");
        }))
                .isInstanceOf(RuntimeException.class)
                .hasMessageStartingWith("db operation failed")
                .hasMessageContaining("bad jsonb value for column foo");

        verify(connection).rollback();
        verify(connection, never()).commit();
    }

    @Test
    void businessExceptionFromWorkPropagatesUnchanged() {
        RuntimeException original = new IllegalStateException("round already resolved");

        assertThatThrownBy(() -> db.tx(c -> {
            throw original;
        })).isSameAs(original);
    }

    @Test
    void successfulWorkCommitsAndNeverRollsBack() throws SQLException {
        Object result = db.tx(c -> "ok");

        assertThat(result).isEqualTo("ok");
        verify(connection).commit();
        verify(connection, never()).rollback();
    }

    @Test
    void rollbackFailureAfterADataErrorSurfacesAsConnectionFailure() throws SQLException {
        doThrow(new SQLException("connection already closed")).when(connection).rollback();

        assertThatThrownBy(() -> db.tx(c -> {
            throw new SQLException("bad jsonb value");
        }))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("db connection failure");
    }
}
