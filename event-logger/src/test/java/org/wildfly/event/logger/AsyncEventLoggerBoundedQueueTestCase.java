/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.wildfly.event.logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Assert;
import org.junit.Test;

/**
 * Tests that verify the bounded-queue and drain-on-close behaviour added by the D20 fix.
 */
public class AsyncEventLoggerBoundedQueueTestCase {

    /**
     * Fills the queue past its capacity and verifies that the drop counter increases.
     * A blocking writer is used so the background thread cannot drain the queue while
     * we are flooding it.
     */
    @Test
    public void dropsEventsWhenQueueFull() throws Exception {
        final CountDownLatch writerBlocked = new CountDownLatch(1);
        final CountDownLatch releaseWriter = new CountDownLatch(1);

        final CountingBlockingWriter blockingWriter =
                new CountingBlockingWriter(writerBlocked, releaseWriter);

        final ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            final AsyncEventLogger logger =
                    new AsyncEventLogger("test-drop", blockingWriter, executor);

            // Submit one event to trigger the background thread and block it.
            final Map<String, Object> d0 = new LinkedHashMap<>();
            d0.put("i", 0);
            logger.log(makeEvent(d0));
            Assert.assertTrue("writer should block within 5s",
                    writerBlocked.await(5, TimeUnit.SECONDS));

            // Flood the queue beyond capacity.
            final int flood = AsyncEventLogger.QUEUE_CAPACITY + 100;
            for (int i = 1; i <= flood; i++) {
                final Map<String, Object> di = new LinkedHashMap<>();
                di.put("i", i);
                logger.log(makeEvent(di));
            }

            Assert.assertTrue("drop count must be > 0, was " + logger.getDroppedCount(),
                    logger.getDroppedCount() > 0);
            Assert.assertTrue("drops must not exceed flood",
                    logger.getDroppedCount() <= (long) flood);
        } finally {
            releaseWriter.countDown();
            executor.shutdown();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    /**
     * Verifies that {@link AsyncEventLogger#close()} drains the queue so that no events
     * are lost during an orderly shutdown.
     *
     * <p>The executor is replaced with a no-op so the background thread never runs;
     * all events stay in the queue until {@code close()} drains them synchronously.
     */
    @Test
    public void closeDrainsPendingEvents() {
        final CollectingWriter collectingWriter = new CollectingWriter();

        // Use a no-op executor so nothing is processed in the background.
        final AsyncEventLogger logger =
                new AsyncEventLogger("test-drain", collectingWriter, r -> { });

        final int count = 20;
        for (int i = 0; i < count; i++) {
            final Map<String, Object> d = new LinkedHashMap<>();
            d.put("seq", i);
            logger.log(makeEvent(d));
        }

        // Nothing written yet — background thread never ran.
        Assert.assertEquals("no writes before close()", 0, collectingWriter.written.size());

        logger.close();

        Assert.assertEquals("all events written after close()", count,
                collectingWriter.written.size());
        Assert.assertEquals("no events dropped", 0L, logger.getDroppedCount());
    }

    /**
     * Verifies that events submitted after {@link AsyncEventLogger#close()} are discarded
     * and counted as dropped.
     */
    @Test
    public void logsAfterCloseAreDropped() {
        final CollectingWriter writer = new CollectingWriter();
        final AsyncEventLogger logger =
                new AsyncEventLogger("test-post-close", writer, Runnable::run);

        logger.close();

        final Map<String, Object> d = new LinkedHashMap<>();
        d.put("k", "v");
        logger.log(makeEvent(d));

        Assert.assertEquals("post-close log must be dropped", 1L, logger.getDroppedCount());
    }

    // -------------------------------------------------------------------------

    private static Event makeEvent(final Map<String, Object> data) {
        return new Event() {
            @Override
            public String getSource() {
                return "test";
            }

            @Override
            public Instant getInstant() {
                return Instant.EPOCH;
            }

            @Override
            public Map<String, Object> getData() {
                return data;
            }
        };
    }

    // -------------------------------------------------------------------------

    private static final class CollectingWriter implements EventWriter {
        final List<Event> written = new ArrayList<>();

        @Override
        public void write(final Event event) {
            written.add(event);
        }

        @Override
        public void close() {
        }
    }

    private static final class CountingBlockingWriter implements EventWriter {
        final AtomicInteger writeCount = new AtomicInteger();
        private final CountDownLatch blocked;
        private final CountDownLatch release;

        CountingBlockingWriter(final CountDownLatch blocked, final CountDownLatch release) {
            this.blocked = blocked;
            this.release = release;
        }

        @Override
        public void write(final Event event) {
            if (writeCount.incrementAndGet() == 1) {
                blocked.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        @Override
        public void close() {
        }
    }
}
