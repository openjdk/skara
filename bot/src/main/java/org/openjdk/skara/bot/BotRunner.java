/*
 * Copyright (c) 2018, 2021, Oracle and/or its affiliates. All rights reserved.
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
import org.openjdk.skara.metrics.Counter;

import java.io.IOException;
import java.nio.file.Path;
import java.net.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.*;
import java.util.stream.Collectors;

import com.sun.net.httpserver.*;

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

    private AtomicInteger workIdCounter = new AtomicInteger();

    private class RunnableWorkItem implements Runnable {
        private static final Counter.WithOneLabel EXCEPTIONS_COUNTER =
            Counter.name("skara_runner_exceptions").labels("bot").register();
        private final WorkItem item;

        RunnableWorkItem(WorkItem wrappedItem) {
            item = wrappedItem;
        }

        public WorkItem get() {
            return item;
        }

        @Override
        public void run() {
            Path scratchPath;

            synchronized (executor) {
                if (scratchPaths.isEmpty()) {
                    log.finer("No scratch paths available - postponing " + item);
                    pending.put(item, Optional.empty());
                    return;
                }
                scratchPath = scratchPaths.removeFirst();
            }

            Collection<WorkItem> followUpItems = null;
            try (var __ = new LogContext(Map.of("work_item", item.toString(),
                    "work_id", String.valueOf(workIdCounter.incrementAndGet())))) {
                log.log(Level.FINE, "Executing item " + item + " on repository " + scratchPath, TaskPhases.BEGIN);
                try {
                    followUpItems = item.run(scratchPath);
                } catch (RuntimeException e) {
                    EXCEPTIONS_COUNTER.labels(item.botName()).inc();
                    log.log(Level.SEVERE, "Exception during item execution (" + item + "): " + e.getMessage(), e);
                    item.handleRuntimeException(e);
                } finally {
                    log.log(Level.FINE, "Item " + item + " is now done", TaskPhases.END);
                }
            }
            if (followUpItems != null) {
                followUpItems.forEach(BotRunner.this::submitOrSchedule);
            }

            synchronized (executor) {
                scratchPaths.addLast(scratchPath);
                active.remove(item);

                // Some of the pending items may now be eligible for execution
                var candidateItems = pending.entrySet().stream()
                                            .filter(e -> e.getValue().isEmpty() || !active.containsKey(e.getValue().get()))
                                            .map(Map.Entry::getKey)
                                            .collect(Collectors.toList());

                // Try the candidates against the current active set
                for (var candidate : candidateItems) {
                    boolean maySubmit = true;
                    for (var activeItem : active.keySet()) {
                        if (!activeItem.concurrentWith(candidate)) {
                            // Still can't run this candidate, leave it pending
                            log.finer("Cannot submit candidate " + candidate + " - not concurrent with " + activeItem);
                            maySubmit = false;
                            break;
                        }
                    }

                    if (maySubmit) {
                        pending.remove(candidate);
                        executor.submit(new RunnableWorkItem(candidate));
                        active.put(candidate, Instant.now());
                        log.finer("Submitting candidate: " + candidate);
                    }
                }
            }
        }
    }

    private final Map<WorkItem, Optional<WorkItem>> pending;
    private final Map<WorkItem, Instant> active;
    private final Deque<Path> scratchPaths;

    private static final Counter.WithOneLabel SCHEDULED_COUNTER =
        Counter.name("skara_runner_scheduled").labels("bot").register();
    private static final Counter.WithOneLabel DISCARDED_COUNTER =
        Counter.name("skara_runner_discarded").labels("bot").register();

    private void submitOrSchedule(WorkItem item) {
        SCHEDULED_COUNTER.labels(item.botName()).inc();
        synchronized (executor) {
            for (var activeItem : active.keySet()) {
                if (!activeItem.concurrentWith(item)) {

                    for (var pendingItem : pending.entrySet()) {
                        // If there are pending items of the same type that we cannot run concurrently with, replace them.
                        if (pendingItem.getKey().getClass().equals(item.getClass()) && !pendingItem.getKey().concurrentWith(item)) {
                            log.finer("Discarding obsoleted item " + pendingItem.getKey() +
                                              " in favor of item " + item);
                            DISCARDED_COUNTER.labels(item.botName()).inc();
                            pending.remove(pendingItem.getKey());
                            // There can't be more than one
                            break;
                        }
                    }

                    pending.put(item, Optional.of(activeItem));
                    return;
                }
            }

            executor.submit(new RunnableWorkItem(item));
            active.put(item, Instant.now());
        }
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

    private void checkPeriodicItems() {
        try (var __ = new LogContext("work_id", String.valueOf(workIdCounter.incrementAndGet()))) {
            log.log(Level.FINE, "Starting of checking for periodic items", TaskPhases.BEGIN);
            try {
                for (var bot : bots) {
                    var items = bot.getPeriodicItems();
                    for (var item : items) {
                        submitOrSchedule(item);
                    }
                }
            } catch (RuntimeException e) {
                log.log(Level.SEVERE, "Exception during periodic item checking: " + e.getMessage(), e);
            } finally {
                log.log(Level.FINE, "Done checking periodic items", TaskPhases.END);
            }
        }
    }

    private void itemWatchdog() {
        synchronized (executor) {
            for (var activeItem : active.entrySet()) {
                var activeDuration = Duration.between(activeItem.getValue(), Instant.now());
                if (activeDuration.compareTo(watchdogWarnTimeout) > 0) {
                    log.severe("Item " + activeItem.getKey() + " has been active more than " + activeDuration +
                                       " - this may be an error!");
                    // Reset the counter to avoid continuous reporting - once every watchdogTimeout is enough
                    activeItem.setValue(Instant.now());
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
        executor.scheduleAtFixedRate(this::itemWatchdog, 0,
                                     config.scheduledExecutionPeriod().toMillis(), TimeUnit.MILLISECONDS);
        executor.scheduleAtFixedRate(this::checkPeriodicItems, 0,
                                     config.scheduledExecutionPeriod().toMillis(), TimeUnit.MILLISECONDS);

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
