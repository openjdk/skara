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

import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.logging.*;
import java.util.stream.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

class TestBotTaskAggregationHandler extends BotTaskAggregationHandler {

    private final Collection<LogRecord> nonTaskRecords;
    private final Collection<List<LogRecord>> taskRecords;

    TestBotTaskAggregationHandler() {
        super(false);
        nonTaskRecords = new ConcurrentLinkedQueue<>();
        taskRecords = new ConcurrentLinkedQueue<>();
    }

    @Override
    public void publishAggregated(List<LogRecord> task) {
        taskRecords.add(task);
    }

    @Override
    public void publishSingle(LogRecord record) {
        nonTaskRecords.add(record);
    }

    Collection<List<LogRecord>> taskRecords() {
        return taskRecords;
    }

    Collection<LogRecord> nonTaskRecords() {
        return nonTaskRecords;
    }
}

class BotTaskAggregationHandlerTests {

    @BeforeAll
    static void setUp() {
        Logger log = Logger.getGlobal();
        log.setLevel(Level.FINEST);
        ConsoleHandler handler = new ConsoleHandler();
        handler.setLevel(Level.FINER);
        log.addHandler(handler);
    }

    @Test
    void simpleNonTask() {
        Logger log = Logger.getGlobal();
        var handler = new TestBotTaskAggregationHandler();
        handler.setLevel(Level.FINER);
        log.addHandler(handler);

        log.fine("Not a task log");

        assertEquals(0, handler.taskRecords().size());
        assertEquals(1, handler.nonTaskRecords().size());
    }

    @Test
    void simpleTask() {
        Logger log = Logger.getGlobal();
        var handler = new TestBotTaskAggregationHandler();
        handler.setLevel(Level.FINER);
        log.addHandler(handler);

        log.log(Level.FINE, "Task log start", BotRunner.TaskPhases.BEGIN);
        log.log(Level.FINE, "Task log end", BotRunner.TaskPhases.END);

        assertEquals(1, handler.taskRecords().size());
        assertEquals(0, handler.nonTaskRecords().size());
    }

    static class ConcurrentTask implements Runnable {

        private final CountDownLatch countDownLatch;
        private final int numLoops;

        ConcurrentTask(CountDownLatch countDownLatch, int numLoops) {
            this.countDownLatch = countDownLatch;
            this.numLoops = numLoops;
        }

        @Override
        public void run() {
            try {
                Logger log = Logger.getGlobal();
                countDownLatch.await();
                for (int i = 0; i < numLoops; ++i) {
                    log.log(Level.FINEST, Long.toString(Thread.currentThread().threadId()), BotRunner.TaskPhases.BEGIN);
                    log.log(Level.FINEST, Long.toString(Thread.currentThread().threadId()), BotRunner.TaskPhases.END);
                    log.log(Level.FINEST, Long.toString(Thread.currentThread().threadId()));
                }
            } catch (InterruptedException e) {
                fail(e);
            }
        }
    }

    @Test
    void concurrentSeparation() {
        final int concurrency = 50;
        final int numLoops = 100;

        Logger log = Logger.getGlobal();
        var handler = new TestBotTaskAggregationHandler();
        handler.setLevel(Level.FINEST);
        log.addHandler(handler);

        var countDownLatch = new CountDownLatch(1);
        var threads = IntStream.range(0, concurrency)
                .mapToObj(num -> new Thread(new ConcurrentTask(countDownLatch, numLoops)))
                .collect(Collectors.toList());
        threads.forEach(Thread::start);

        countDownLatch.countDown();
        threads.forEach(thread -> {
            try {
                thread.join();
            } catch (InterruptedException e) {
                fail(e);
            }
        });

        assertEquals(concurrency * numLoops, handler.taskRecords().size());
        assertEquals(concurrency * numLoops, handler.nonTaskRecords().size());

        handler.taskRecords().stream()
               .flatMap(Collection::stream)
               .forEach(record -> assertEquals(Long.toString(record.getLongThreadID()), record.getMessage()));
        handler.nonTaskRecords()
               .forEach(record -> assertEquals(Long.toString(record.getLongThreadID()), record.getMessage()));
    }

}
