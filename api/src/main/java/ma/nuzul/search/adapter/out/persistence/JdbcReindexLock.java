package ma.nuzul.search.adapter.out.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.function.Supplier;
import javax.sql.DataSource;
import ma.nuzul.search.application.ReindexLockPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.stereotype.Component;

/**
 * {@link ReindexLockPort} on a session-level Postgres advisory lock held on a dedicated pooled connection, taken
 * straight from the DataSource so it is never bound to a Spring transaction: the work's reads run on other
 * connections, each in its own short transaction. Two-int form, like the reservation hold lock; the
 * {@code nuzul.search} namespace keeps it apart from every other advisory lock in the database. It conflicts with
 * transaction-level advisory locks on the same key too.
 *
 * <p>Released on every path: unlocked in {@code finally}. A session lock outlives a pooled connection's return to the
 * pool, so if the unlock fails or reports the lock was not held, the connection is aborted rather than returned:
 * closing the physical connection ends the session, which releases the lock. A crashed instance's connection closes
 * the same way.
 *
 * <p>Assumes a direct connection to Postgres, one session per pooled connection, for as long as a reindex runs.
 * Behind a PgBouncer in transaction pooling mode, the lock and the unlock could run on different server sessions (the
 * lock then leaks, or another client's session holds it); and an {@code idle_session_timeout} shorter than a reindex
 * would end this idle session, releasing the lock while the work still runs, so a second reindex could start.
 */
@Component
class JdbcReindexLock implements ReindexLockPort {

    static final String TRY_LOCK = "SELECT pg_try_advisory_lock(hashtext('nuzul.search'), hashtext('reindex'))";
    static final String UNLOCK = "SELECT pg_advisory_unlock(hashtext('nuzul.search'), hashtext('reindex'))";

    private static final Logger log = LoggerFactory.getLogger(JdbcReindexLock.class);

    private final DataSource dataSource;

    JdbcReindexLock(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public <T> Optional<T> runExclusively(Supplier<T> work) {
        Connection connection = connect();
        boolean released = false;
        try {
            if (!query(connection, TRY_LOCK)) {
                released = true; // nothing taken
                return Optional.empty();
            }
            try {
                return Optional.of(work.get());
            } finally {
                released = unlock(connection);
            }
        } catch (SQLException e) {
            throw new DataAccessResourceFailureException("reindex lock: " + e.getMessage(), e);
        } finally {
            close(connection, released);
        }
    }

    private Connection connect() {
        try {
            return dataSource.getConnection();
        } catch (SQLException e) {
            throw new DataAccessResourceFailureException("reindex lock: no connection", e);
        }
    }

    /** True when the lock is known to be released; failures are logged, never thrown over the work's own outcome. */
    private static boolean unlock(Connection connection) {
        try {
            boolean unlocked = query(connection, UNLOCK);
            if (!unlocked) {
                log.warn("Reindex lock was not held at unlock; dropping its connection");
            }
            return unlocked;
        } catch (SQLException | RuntimeException e) {
            log.warn("Reindex lock unlock failed; dropping its connection to release it: {}", e.toString());
            return false;
        }
    }

    private static void close(Connection connection, boolean released) {
        try {
            if (!released) {
                // Ends the physical session (and its lock); the close below then hands the dead connection back to
                // the pool, which drops it instead of reusing it.
                connection.abort(Runnable::run);
            }
            connection.close();
        } catch (SQLException e) {
            log.warn("Reindex lock connection could not be closed: {}", e.toString());
        }
    }

    private static boolean query(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet result = statement.executeQuery()) {
            result.next();
            return result.getBoolean(1);
        }
    }
}
