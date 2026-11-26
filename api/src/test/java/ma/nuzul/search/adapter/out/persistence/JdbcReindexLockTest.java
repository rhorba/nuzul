package ma.nuzul.search.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.Executor;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * Story 3.2: the reindex lock's release paths a real Postgres will not produce on demand. When the unlock fails or
 * finds no lock, the connection is aborted (ending the session and its lock) instead of going back to the pool with
 * the lock still held; the work's own outcome is never replaced by the unlock failure.
 */
class JdbcReindexLockTest {

    private final DataSource dataSource = mock(DataSource.class);
    private final Connection connection = mock(Connection.class);
    private final JdbcReindexLock lock = new JdbcReindexLock(dataSource);

    JdbcReindexLockTest() throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
        PreparedStatement tryLock = statementReturning(true);
        when(connection.prepareStatement(JdbcReindexLock.TRY_LOCK)).thenReturn(tryLock);
    }

    private static PreparedStatement statementReturning(boolean value) throws SQLException {
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet result = mock(ResultSet.class);
        when(statement.executeQuery()).thenReturn(result);
        when(result.next()).thenReturn(true);
        when(result.getBoolean(1)).thenReturn(value);
        return statement;
    }

    @Test
    void anUnlockThatFailsAbortsTheConnectionAndKeepsTheWorksResult() throws SQLException {
        when(connection.prepareStatement(JdbcReindexLock.UNLOCK)).thenThrow(new SQLException("connection reset"));

        assertThat(lock.runExclusively(() -> "done")).contains("done");

        InOrder order = inOrder(connection);
        order.verify(connection).abort(any(Executor.class));
        order.verify(connection).close();
    }

    @Test
    void anUnlockThatFindsNoLockAbortsTheConnection() throws SQLException {
        PreparedStatement unlock = statementReturning(false);
        when(connection.prepareStatement(JdbcReindexLock.UNLOCK)).thenReturn(unlock);

        assertThat(lock.runExclusively(() -> "done")).contains("done");

        verify(connection).abort(any(Executor.class));
        verify(connection).close();
    }

    @Test
    void aFailingUnlockAfterFailingWorkStillThrowsTheWorksFailure() throws SQLException {
        when(connection.prepareStatement(JdbcReindexLock.UNLOCK)).thenThrow(new SQLException("connection reset"));
        var failure = new IllegalStateException("bulk rejected");

        assertThatThrownBy(() -> lock.runExclusively(() -> {
                    throw failure;
                }))
                .isSameAs(failure);

        verify(connection).abort(any(Executor.class));
        verify(connection).close();
    }

    @Test
    void aCleanUnlockReturnsTheConnectionToThePool() throws SQLException {
        PreparedStatement unlock = statementReturning(true);
        when(connection.prepareStatement(JdbcReindexLock.UNLOCK)).thenReturn(unlock);

        assertThat(lock.runExclusively(() -> "done")).contains("done");

        verify(connection, never()).abort(any(Executor.class));
        verify(connection).close();
    }
}
