/*
 * Copyright (c) 2018, 2019, Oracle and/or its affiliates. All rights reserved.
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

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.*;

public abstract class BotTaskAggregationHandler extends StreamHandler {

    private static class ThreadLogs {
        boolean isPublishing;
        boolean inTask;
        List<LogRecord> logs;

        ThreadLogs() {
            isPublishing = false;
            clear();
        }

        void clear() {
            inTask = false;
            logs = new ArrayList<>();
        }
    }

    private final Map<Long, ThreadLogs> threadLogs;
    private final Logger log;
    // Should this class handle log level filtering or leave that to the subclass
    private final boolean filterOnLevel;

    public BotTaskAggregationHandler(boolean filterOnLevel) {
        this.filterOnLevel = filterOnLevel;
        threadLogs = new ConcurrentHashMap<>();
        log = Logger.getLogger("org.openjdk.skara.bot");
    }

    private boolean hasMarker(LogRecord record, BotRunner.TaskPhases marker) {
        if (record.getParameters() == null) {
            return false;
        }
        return Arrays.asList(record.getParameters()).contains(marker);
    }

    @Override
    public final void publish(LogRecord record) {
        var newEntry = new ThreadLogs();
        var threadEntry = threadLogs.putIfAbsent(record.getLongThreadID(), newEntry);
        if (threadEntry == null) {
            threadEntry = newEntry;
        }

        // Avoid potential recursive log output
        if (threadEntry.isPublishing) {
            return;
        }
        threadEntry.isPublishing = true;

        try {
            if (!threadEntry.inTask) {
                if (!hasMarker(record, BotRunner.TaskPhases.BEGIN)) {
                    if (!filterOnLevel || record.getLevel().intValue() >= getLevel().intValue()) {
                        publishSingle(record);
                    }
                    return;
                }
                threadEntry.inTask = true;
            }
            if (!filterOnLevel || record.getLevel().intValue() >= getLevel().intValue()) {
                threadEntry.logs.add(record);
            }

            if (hasMarker(record, BotRunner.TaskPhases.END)) {
                publishAggregated(threadEntry.logs);
                threadEntry.clear();
            }
        }
        catch (RuntimeException e) {
            log.log(Level.SEVERE, "Exception during task notification posting: " + e.getMessage(), e);
        } finally {
            threadEntry.isPublishing = false;
        }
    }

    public abstract void publishAggregated(List<LogRecord> task);
    public abstract void publishSingle(LogRecord record);
}
