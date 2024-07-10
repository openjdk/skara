/*
 * Copyright (c) 2018, 2024, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package org.openjdk.skara.bot;

import java.util.concurrent.atomic.AtomicInteger;
import org.openjdk.skara.json.JSONValue;
import org.openjdk.skara.metrics.*;

import java.io.IOException;
import java.nio.file.Path;
import java.net.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.*;
import java.lang.management.ManagementFactory;
import com.sun.management.ThreadMXBean;

import com.sun.net.httpserver.*;
import org.openjdk.skara.network.RestRequest;
import org.openjdk.skara.network.UncheckedRestException;

class BotRunnerError extends RuntimeException {
    BotRunnerError(String msg) {
        super(msg);
    }

    BotRunnerError(String msg, Throwable suppressed) {
        super(msg);
        addSuppressed(suppressed);
    }
}

public class BotRunner {
    enum TaskPhases {
        BEGIN,
        END
    }

    private final AtomicInteger workIdCounter = new AtomicInteger();

    /**
     * A wrapper for a WorkItem while it's tracked as pending. Used to track
     * when a particular WorkItem entered the pending state so that metrics
     * and log messages can use this information.
     */
    private static class PendingWorkItem {
        private final WorkItem item;
        private final Instant createTime;

        public PendingWorkItem(WorkItem item) {
            this(item, null);
        }

        public PendingWorkItem(WorkItem item, Instant originalCreateTime) {
            this.item = item;
            if (originalCreateTime != null) {
                this.createTime = originalCreateTime;
            } else {
                this.createTime = Instant.now();
            }
        }
    }

    private class RunnableWorkItem implements Runnable {
        private static final Counter.WithThreeLabels EXCEPTIONS_COUNTER =
            Counter.name("skara_runner_exceptions").labels("bot", "work_item", "exception").register();
        /**
         * Gauge that tracks the time WorkItems have been pending before
         * being submitted.
         */
        private static final Gauge.WithTwoLabels PENDING_TIME_GAUGE =
            Gauge.name("skara_runner_pending_time").labels("bot", "work_item").register();
        /**
         * Gauge that tracks the time WorkItems have been submitted before
         * starting to run.
         */
        private static final Gauge.WithTwoLabels SUBMITTED_TIME_GAUGE =
                Gauge.name("skara_runner_submitted_time").labels("bot", "work_item").register();
        private static final Counter.WithTwoLabels TIME_COUNTER =
                Counter.name("skara_runner_run_time_total").labels("bot", "work_item").register();
        private static final Counter.WithTwoLabels ITEM_FINISHED_COUNTER =
                Counter.name("skara_runner_finished_counter").labels("bot", "work_item").register();
        private static final Counter.WithTwoLabels CPU_TIME_COUNTER =
                Counter.name("skara_runner_cpu_time_total").labels("bot", "work_item").register();
        private static final Counter.WithTwoLabels ALLOCATED_BYTES_COUNTER =
                Counter.name("skara_runner_allocated_bytes_total").labels("bot", "work_item").register();

        private final WorkItem item;
        private final int workId = workIdCounter.incrementAndGet();
        private final Instant createTime = Instant.now();
        // This gets updated by the watchdog when a timeout occurs to avoid
        // repeating the timeout log messages too often.
        private Instant timeoutWarningTime = createTime;

        RunnableWorkItem(WorkItem wrappedItem) {
            item = wrappedItem;
        }

        public WorkItem get() {
            return item;
        }

        private static Optional<ThreadMXBean> getThreadMXBean() {
            var bean = ManagementFactory.getThreadMXBean();
            return bean instanceof ThreadMXBean b ?
                Optional.of(b) : Optional.empty();
        }

        private static void enableThreadCpuTime() {
            var bean = getThreadMXBean();
            if (bean.get().isCurrentThreadCpuTimeSupported() && !bean.get().isThreadCpuTimeEnabled()) {
                bean.get().setThreadCpuTimeEnabled(true);
            }
        }

        private static long getCurrentThreadCpuTime() {
            var bean = getThreadMXBean();
            if (bean.isEmpty()) {
                return -1L;
            }
            return bean.get().isCurrentThreadCpuTimeSupported()?
                bean.get().getCurrentThreadCpuTime() :
                -1L;
        }

        private static long getCurrentThreadAllocatedBytes() {
            var bean = getThreadMXBean();
            if (bean.isEmpty()) {
                return -1L;
            }

            if (!bean.get().isThreadAllocatedMemorySupported()) {
                return -1L;
            }

            if (!bean.get().isThreadAllocatedMemoryEnabled()) {
                bean.get().setThreadAllocatedMemoryEnabled(true);
            }

            return bean.get().getCurrentThreadAllocatedBytes();
        }

        @Override
        public void run() {
            enableThreadCpuTime();
            long startCpuTimeNs = getCurrentThreadCpuTime();
            long startAllocatedBytes = getCurrentThreadAllocatedBytes();
            var start = Instant.now();

            try {
                runMeasured();
            } finally {
                ITEM_FINISHED_COUNTER.labels(item.botName(), item.workItemName()).inc();
                long stopCpuTimeNs = getCurrentThreadCpuTime();
                long stopAllocatedBytes = getCurrentThreadAllocatedBytes();

                var cpuTimeNs = (startCpuTimeNs == -1L && stopCpuTimeNs == -1L)?
                    -1L : stopCpuTimeNs - startCpuTimeNs;
                var allocatedBytes = (startAllocatedBytes == -1L && stopAllocatedBytes == -1L)?
                    -1L : stopAllocatedBytes - startAllocatedBytes;

                if (cpuTimeNs != -1L) {
                    double cpuTimeSeconds = cpuTimeNs / 1_000_000_000.0;
                    CPU_TIME_COUNTER.labels(item.botName(), item.workItemName()).inc(cpuTimeSeconds);
                }
                if (allocatedBytes != -1L) {
                    ALLOCATED_BYTES_COUNTER.labels(item.botName(), item.workItemName()).inc(allocatedBytes);
                }
                TIME_COUNTER.labels(item.botName(), item.workItemName()).inc(
                        Duration.between(start, Instant.now()).toMillis() / 1_000.0);
            }
        }

        private void runMeasured() {
            Path scratchPath;

            synchronized (executor) {
                if (scratchPaths.isEmpty()) {
                    log.warning("No scratch paths available - postponing " + item);
                    addPending(new PendingWorkItem(item), null);
                    return;
                }
                scratchPath = scratchPaths.removeFirst();
            }

            Collection<WorkItem> followUpItems = null;
            var start = Instant.now();
            try (var __ = new LogContext(Map.of("work_item", item.toString(),
                    "work_id", String.valueOf(workId)))) {
                var submittedDuration = Duration.between(createTime, start);
                SUBMITTED_TIME_GAUGE.labels(item.botName(), item.workItemName()).set(submittedDuration.toMillis() / 1_000.0);
                log.log(Level.FINE, "Executing item " + item + " on repository " + scratchPath
                        + " after being submitted for " + submittedDuration,
                        new Object[]{TaskPhases.BEGIN, submittedDuration});
                try {
                    followUpItems = item.run(scratchPath);
                } catch (UncheckedRestException e) {
                    EXCEPTIONS_COUNTER.labels(item.botName(), item.workItemName(), e.getClass().getName()).inc();
                    // Log as WARNING to avoid triggering alarms. Failed REST calls are tracked
                    // using metrics.
                    log.log(Level.WARNING, "RestException during item execution (" + item + "): "
                            + e.getMessage(), e);
                    item.handleRuntimeException(e);
                } catch (RuntimeException e) {
                    EXCEPTIONS_COUNTER.labels(item.botName(), item.workItemName(), e.getClass().getName()).inc();
                    if (e.getCause() instanceof UncheckedRestException) {
                        // Log as WARNING to avoid triggering alarms. Failed REST calls are tracked
                        // using metrics.
                        log.log(Level.WARNING, "RestException during item execution (" + item + ")"
                                + e.getCause().getMessage(), e.getCause());
                    } else {
                        log.log(Level.SEVERE, "Exception during item execution (" + item + "): " + e.getMessage(), e);
                    }
                    item.handleRuntimeException(e);
                } catch (Error e) {
                    EXCEPTIONS_COUNTER.labels(item.botName(), item.workItemName(), e.getClass().getName()).inc();
                    log.log(Level.SEVERE, "Error thrown during item execution: (" + item + "): " + e.getMessage(), e);
                    throw e;
                } finally {
                    var duration = Duration.between(start, Instant.now());
                    log.log(Level.FINE, "Item " + item + " is now done after " + duration,
                            new Object[]{TaskPhases.END, duration});
                    synchronized (executor) {
                        scratchPaths.addLast(scratchPath);
                        done(item);
                    }
                }
                if (followUpItems != null) {
                    followUpItems.forEach(BotRunner.this::submitOrSchedule);
                }

                synchronized (executor) {
                    // Some of the pending items may now be eligible for execution
                    var candidateItems = pending.entrySet().stream()
                            .filter(e -> e.getValue().isEmpty() || !active.containsKey(e.getValue().get()))
                            .map(Map.Entry::getKey)
                            .toList();

                    // Try the candidates against the current active set
                    for (var candidate : candidateItems) {
                        boolean maySubmit = true;
                        for (var activeItem : active.keySet()) {
                            if (!activeItem.concurrentWith(candidate.item)) {
                                // Still can't run this candidate, leave it pending
                                log.finer("Cannot submit candidate " + candidate + " - not concurrent with " + activeItem);
                                maySubmit = false;
                                break;
                            }
                        }

                        if (maySubmit) {
                            removePending(candidate);
                            submit(candidate.item);
                            var timeSinceCreation = Duration.between(candidate.createTime, Instant.now());
                            PENDING_TIME_GAUGE.labels(candidate.item.botName(), candidate.item.workItemName())
                                    .set(timeSinceCreation.toMillis() / 1_000.0);
                            log.log(Level.FINE, "Submitting item " + candidate.item
                                    + " after being pending for " + timeSinceCreation, timeSinceCreation);
                        }
                    }
                }
            }
        }
    }

    // Mapping of pending items to the active item preventing them from running
    private final Map<PendingWorkItem, Optional<WorkItem>> pending;
    // Mapping of active WorkItem to their RunnableWorkItem
    private final Map<WorkItem, RunnableWorkItem> active;
    private final Deque<Path> scratchPaths;

    private static final Counter.WithTwoLabels SCHEDULED_COUNTER =
            Counter.name("skara_runner_scheduled_counter").labels("bot", "work_item").register();
    private static final Counter.WithTwoLabels PENDING_COUNTER =
            Counter.name("skara_runner_pending_counter").labels("bot", "work_item").register();
    private static final Counter.WithTwoLabels SUBMITTED_COUNTER =
            Counter.name("skara_runner_submitted_counter").labels("bot", "work_item").register();
    private static final Counter.WithTwoLabels DISCARDED_COUNTER =
            Counter.name("skara_runner_discarded_counter").labels("bot", "work_item").register();
    /**
     * Gauge that tracks the number of active WorkItems for each kind
     */
    private static final Gauge.WithTwoLabels ACTIVE_GAUGE =
            Gauge.name("skara_runner_active").labels("bot", "work_item").register();
    /**
     * Gauge that tracks the number of pending WorkItems for each kind
     */
    private static final Gauge.WithTwoLabels PENDING_GAUGE =
            Gauge.name("skara_runner_pending").labels("bot", "work_item").register();

    private void submitOrSchedule(WorkItem item) {
        SCHEDULED_COUNTER.labels(item.botName(), item.workItemName()).inc();
        synchronized (executor) {
            for (var activeItem : active.keySet()) {
                if (!activeItem.concurrentWith(item)) {

                    Instant originalCreateTime = null;
                    for (var pendingItem : pending.keySet()) {
                        // If there are pending items of the same type that we cannot run concurrently with, replace them.
                        if (item.replaces(pendingItem.item)) {
                            log.finer("Discarding obsoleted item " + pendingItem +
                                              " in favor of item " + item);
                            DISCARDED_COUNTER.labels(item.botName(), item.workItemName()).inc();
                            removePending(pendingItem);
                            originalCreateTime = pendingItem.createTime;
                            // There can't be more than one
                            break;
                        }
                    }

                    log.fine("Adding pending item " + item);
                    addPending(new PendingWorkItem(item, originalCreateTime), activeItem);
                    return;
                }
            }
            log.fine("Submitting item " + item);
            submit(item);
        }
    }

    /**
     * Called to add a WorkItem to the pending queue
     * @param pendingItem Item to queue
     * @param activeItem Optional active item that this item is waiting for
     */
    private void addPending(PendingWorkItem pendingItem, WorkItem activeItem) {
        pending.put(pendingItem, Optional.ofNullable(activeItem));
        PENDING_GAUGE.labels(pendingItem.item.botName(), pendingItem.item.workItemName()).inc();
        PENDING_COUNTER.labels(pendingItem.item.botName(), pendingItem.item.workItemName()).inc();
    }

    /**
     * Called to remove an item from the pending queue.
     */
    private void removePending(PendingWorkItem pendingItem) {
        pending.remove(pendingItem);
        PENDING_GAUGE.labels(pendingItem.item.botName(), pendingItem.item.workItemName()).dec();
    }

    /**
     * Called to submit a WorkItem for execution
     */
    private void submit(WorkItem item) {
        RunnableWorkItem runnableWorkItem = new RunnableWorkItem(item);
        executor.submit(runnableWorkItem);
        active.put(item, runnableWorkItem);
        ACTIVE_GAUGE.labels(item.botName(), item.workItemName()).inc();
        SUBMITTED_COUNTER.labels(item.botName(), item.workItemName()).inc();
    }

    /**
     * Called when a WorkItem is done executing
     */
    private void done(WorkItem item) {
        active.remove(item);
        ACTIVE_GAUGE.labels(item.botName(), item.workItemName()).dec();
    }

    private void drain(Duration timeout) throws TimeoutException {
        Instant start = Instant.now();

        while (Instant.now().isBefore(start.plus(timeout))) {
            while (true) {
                var head = (ScheduledFuture<?>) executor.getQueue().peek();
                if (head != null) {
                    log.fine("Waiting for future to complete");
                    try {
                        head.get();
                    } catch (InterruptedException | ExecutionException e) {
                        log.log(Level.WARNING, "Exception during queue drain", e);
                    }
                } else {
                    log.finest("Queue is now empty");
                    break;
                }
            }

            synchronized (executor) {
                if (pending.isEmpty() && active.isEmpty()) {
                    log.fine("Nothing awaiting scheduling - drain is finished");
                    return;
                } else {
                    log.finest("Waiting for flighted tasks");
                }
            }
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                log.log(Level.WARNING, "Exception during queue drain", e);
            }
        }

        throw new TimeoutException();
    }

    private final BotRunnerConfiguration config;
    private final List<Bot> bots;
    private final ScheduledThreadPoolExecutor executor;
    private final BotWatchdog botWatchdog;
    private final Duration watchdogWarnTimeout;
    private volatile boolean isReady;
    private volatile boolean isHealthy;

    private static final Logger log = Logger.getLogger("org.openjdk.skara.bot");

    public BotRunner(BotRunnerConfiguration config, List<Bot> bots) {
        this.config = config;
        this.bots = bots;

        pending = new HashMap<>();
        active = new HashMap<>();
        scratchPaths = new LinkedList<>();

        for (int i = 0; i < config.concurrency(); ++i) {
            var folder = config.scratchFolder().resolve("scratch-" + i);
            scratchPaths.addLast(folder);
        }

        executor = new ScheduledThreadPoolExecutor(config.concurrency());
        botWatchdog = new BotWatchdog(config.watchdogTimeout(), () -> isHealthy = false);
        watchdogWarnTimeout = config.watchdogWarnTimeout();
        isReady = false;
        isHealthy = true;
    }

    boolean isReady() {
        return isReady;
    }

    boolean isHealthy() {
        return isHealthy;
    }

    private static final Gauge PERIODIC_CHECK_TIME_GAUGE =
            Gauge.name("skara_runner_check_time_gauge").register();
    private static final Counter.WithOneLabel PERIODIC_CHECK_TIME =
            Counter.name("skara_runner_check_time").labels("bot").register();

    private void checkPeriodicItems() {
        try (var __ = new LogContext("work_id", String.valueOf(workIdCounter.incrementAndGet()))) {
            Instant start = Instant.now();
            log.log(Level.FINE, "Start of checking for periodic items", TaskPhases.BEGIN);
            try {
                for (var bot : bots) {
                    Instant botStart = Instant.now();
                    try (var ___ = new LogContext("bot", bot.toString())) {
                        log.fine("Start of checking for periodic items for " + bot);
                        var items = bot.getPeriodicItems();
                        for (var item : items) {
                            submitOrSchedule(item);
                        }
                    } catch (UncheckedRestException e) {
                        // Log as WARNING to avoid triggering alarms. Failed REST calls are tracked
                        // using metrics.
                        log.log(Level.WARNING, "RestException during periodic items checking: " + e.getMessage(), e);
                    } catch (RuntimeException e) {
                        log.log(Level.SEVERE, "Exception during periodic items checking: " + e.getMessage(), e);
                    } finally {
                        var duration = Duration.between(botStart, Instant.now());
                        log.log(Level.FINE, "Checking for periodic items for " + bot + " took " + duration, duration);
                        PERIODIC_CHECK_TIME.labels(bot.name()).inc(duration.toMillis() / 1_000.0);
                    }
                }
            } finally {
                var duration = Duration.between(start, Instant.now());
                log.log(Level.FINE, "Checking periodic items took " + duration,
                        new Object[]{TaskPhases.END, duration});
                PERIODIC_CHECK_TIME_GAUGE.set(duration.toMillis() / 1_000.0);
            }
        }
    }

    private void itemWatchdog() {
        synchronized (executor) {
            for (var activeRunnableItem : active.values()) {
                Instant now = Instant.now();
                var timeoutDuration = Duration.between(activeRunnableItem.timeoutWarningTime, now);
                if (timeoutDuration.compareTo(watchdogWarnTimeout) > 0) {
                    log.severe("Item " + activeRunnableItem.item + " with workId " + activeRunnableItem.workId + " has been active more than " +
                            Duration.between(activeRunnableItem.createTime, now) + " - this may be an error!");
                    // Reset the counter to avoid continuous reporting - once every watchdogTimeout is enough
                    activeRunnableItem.timeoutWarningTime = now;
                }
            }
            // Inform the global watchdog that the scheduler is still executing items
            log.fine("Pinging Watchdog");
            botWatchdog.ping();
        }
    }

    void processWebhook(JSONValue request) {
        try (var __ = new LogContext("work_id", String.valueOf(workIdCounter.incrementAndGet()))) {
            log.log(Level.FINE, "Starting processing of incoming rest request", TaskPhases.BEGIN);
            log.fine("Request: " + request);
            try {
                for (var bot : bots) {
                    var items = bot.processWebHook(request);
                    for (var item : items) {
                        submitOrSchedule(item);
                    }
                }
            } catch (RuntimeException e) {
                log.log(Level.SEVERE, "Exception during rest request processing: " + e.getMessage(), e);
            } finally {
                log.log(Level.FINE, "Done processing incoming rest request", TaskPhases.END);
            }
        }
    }

    public void run() {
        run(Duration.ofDays(10 * 365));
    }

    public void run(Duration timeout) {
        log.info("Periodic task interval: " + config.scheduledExecutionPeriod());
        log.info("Concurrency: " + config.concurrency());

        HttpServer server = null;
        var serverConfig = config.httpServer(this);
        if (serverConfig.isPresent()) {
            try {
                var port = serverConfig.get().port();
                var address = new InetSocketAddress(port);
                server = HttpServer.create(address, 0);
                server.setExecutor(null);
                for (var context : serverConfig.get().contexts()) {
                    server.createContext(context.path(), context.handler());
                }
                server.start();
            } catch (IOException e) {
                log.log(Level.WARNING, "Failed to create HTTP server", e);
            }
        }

        isReady = true;

        var schedulingInterval = config.scheduledExecutionPeriod().toMillis();
        executor.scheduleAtFixedRate(this::itemWatchdog, 0, schedulingInterval, TimeUnit.MILLISECONDS);
        executor.scheduleAtFixedRate(this::checkPeriodicItems, 0, schedulingInterval, TimeUnit.MILLISECONDS);

        var cacheEvictionInterval = config.cacheEvictionInterval().toMillis();
        executor.scheduleAtFixedRate(RestRequest::evictOldCacheData, cacheEvictionInterval,
                cacheEvictionInterval, TimeUnit.MILLISECONDS);

        try {
            executor.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        if (server != null) {
            server.stop(0);
        }
        executor.shutdown();
    }

    public void runOnce(Duration timeout) throws TimeoutException {
        log.info("Starting BotRunner execution, will run once");
        log.info("Timeout: " + timeout);
        log.info("Concurrency: " + config.concurrency());

        var periodics = executor.submit(this::checkPeriodicItems);
        try {
            log.fine("Make sure periodics execute at least once");
            periodics.get();
            log.fine("Periodics have now run");
        } catch (InterruptedException e) {
            throw new BotRunnerError("Interrupted", e);
        } catch (ExecutionException e) {
            throw new BotRunnerError("Execution error", e);
        }
        log.fine("Waiting for all spawned tasks");
        drain(timeout);

        log.fine("Done waiting for all tasks");
        executor.shutdown();
    }
}
