package com.p2p.peer;

/**
 * Caps this peer's total upload speed (shared by all concurrent uploads). On localhost transfers are
 * otherwise instant, so the limit makes multi-peer downloads visible in a demo. 0 = unlimited.
 */
final class RateLimiter {
    private final long bytesPerSecond;
    private long nextFreeNanos = System.nanoTime();

    RateLimiter(long bytesPerSecond) {
        this.bytesPerSecond = bytesPerSecond;
    }

    long bytesPerSecond() {
        return bytesPerSecond;
    }

    void acquire(int bytes) throws InterruptedException {
        if (bytesPerSecond <= 0) {
            return;
        }
        long wait;
        synchronized (this) {
            long now = System.nanoTime();
            if (nextFreeNanos < now) {
                nextFreeNanos = now;
            }
            wait = nextFreeNanos - now;
            nextFreeNanos += bytes * 1_000_000_000L / bytesPerSecond;
        }
        if (wait > 0) {
            Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
        }
    }
}
