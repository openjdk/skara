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
package org.openjdk.skara.bots.cli;

import org.openjdk.skara.bot.*;
import org.openjdk.skara.json.JSON;

import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.logging.*;

public class LoggingBot implements Bot, WorkItem {

    private final Consumer<Logger> runnable;
    private final Logger logger;

    LoggingBot(Logger logger, Consumer<Logger> runnable) {
        this.runnable = runnable;
        this.logger = logger;
    }

    @Override
    public List<WorkItem> getPeriodicItems() {
        return List.of(this);
    }


    public static void runOnce(StreamHandler handler, Level handlerLevel, Consumer<Logger> runnable) {
        var log = Logger.getLogger("org.openjdk.skara.bot");
        log.setLevel(Level.FINEST);
        handler.setLevel(handlerLevel);
        log.addHandler(handler);
        var bot = new LoggingBot(log, runnable);

        try {
            var config = JSON.object().put("scratch", JSON.object().put("path", "/tmp"));
            var runner = new BotRunner(BotRunnerConfiguration.parse(config), List.of(bot));
            runner.runOnce(Duration.ofMinutes(10));
        } catch (TimeoutException | ConfigurationError e) {
            throw new RuntimeException(e);
        }

        log.removeHandler(handler);
    }

    public static void runOnce(StreamHandler handler, Consumer<Logger> runnable) {
        runOnce(handler, Level.WARNING, runnable);
    }

    @Override
    public boolean concurrentWith(WorkItem other) {
        return false;
    }

    @Override
    public Collection<WorkItem> run(Path scratchPath) {
        runnable.accept(logger);
        return List.of();
    }
}
