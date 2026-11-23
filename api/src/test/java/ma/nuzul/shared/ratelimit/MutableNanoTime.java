package ma.nuzul.shared.ratelimit;

import java.time.Duration;
import java.util.function.LongSupplier;

/** A monotonic {@code System.nanoTime()} stand-in the test moves by hand (log throttle, reconnect back-off). */
final class MutableNanoTime implements LongSupplier {

    /** Not zero, like the real nanoTime: elapsed-time code must not assume an origin. */
    private volatile long now = 123_456_789_000L;

    void advance(Duration by) {
        now += by.toNanos();
    }

    @Override
    public long getAsLong() {
        return now;
    }
}
