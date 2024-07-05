/*
 * Copyright (c) 2020, 2024, Oracle and/or its affiliates. All rights reserved.
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

import java.time.Duration;

public class BotWatchdog {
    private final Thread watchThread;
    private final Duration maxWait;
    private final Runnable callBack;
    private volatile boolean hasBeenPinged = false;

    private void threadMain() {
        while (true) {
            try {
                Thread.sleep(maxWait);
                if (!hasBeenPinged) {
                    System.out.println("No watchdog ping detected for " + maxWait + " - exiting...");
                    callBack.run();
                    System.exit(1);
                }
                hasBeenPinged = false;
            } catch (InterruptedException ignored) {
            }
        }
    }

    BotWatchdog(Duration maxWait, Runnable callBack) {
        this.maxWait = maxWait;
        this.callBack = callBack;
        watchThread = new Thread(this::threadMain);
        watchThread.setName("BotWatchdog");
        watchThread.setDaemon(true);
        watchThread.start();
    }

    public void ping() {
        hasBeenPinged = true;
    }
}
