/*
 * Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.test;

import org.openjdk.skara.bot.*;

import java.io.IOException;
import java.util.*;

public class TestBotRunner {
    @FunctionalInterface
    public interface AfterItemHook {
        void run(WorkItem item);
    }

    public static void runPeriodicItems(Bot bot) throws IOException {
        runPeriodicItems(bot, item -> {});
    }

    public static void runPeriodicItems(Bot bot, AfterItemHook afterItemHook) throws IOException {
        var items = new LinkedList<>(bot.getPeriodicItems());
        for (var item = items.pollFirst(); item != null; item = items.pollFirst()) {
            Collection<WorkItem> followUpItems = null;
            try (var scratchFolder = new TemporaryDirectory()) {
                followUpItems = item.run(scratchFolder.path());
                afterItemHook.run(item);
            } catch (RuntimeException e) {
                item.handleRuntimeException(e);
                // Allow tests to assert on these as well
                throw e;
            }
            if (followUpItems != null) {
                items.addAll(followUpItems);
            }
        }
    }
}
