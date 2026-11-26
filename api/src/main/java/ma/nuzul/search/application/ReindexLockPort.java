package ma.nuzul.search.application;

import java.util.Optional;
import java.util.function.Supplier;

/** Out-port: the cluster-wide "one reindex at a time" lock (story 3.2). */
public interface ReindexLockPort {

    /**
     * Runs {@code work} while holding the reindex lock, taken without waiting. The lock is held outside any transaction
     * {@code work} uses, so {@code work}'s own reads each see the latest commits, and it is released on every path
     * (return or exception) before this method returns.
     *
     * @return {@code work}'s result, or empty without running it when another reindex (any replica) holds the lock
     */
    <T> Optional<T> runExclusively(Supplier<T> work);
}
