/*
 * Copyright (c) 2019 Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.ci;

import java.util.List;

public interface Job {
    static class Status {
        private final int numCompleted;
        private final int numRunning;
        private final int numNotStarted;

        public Status(int numCompleted, int numRunning, int numNotStarted) {
            this.numCompleted = numCompleted;
            this.numRunning = numRunning;
            this.numNotStarted = numNotStarted;
        }

        public int numCompleted() {
            return numCompleted;
        }

        public int numRunning() {
            return numRunning;
        }

        public int numNotStarted() {
            return numNotStarted;
        }

        public int numTotal() {
            return numCompleted + numRunning + numNotStarted;
        }
    }

    static class Result {
        private final int numPassed;
        private final int numFailed;
        private final int numSkipped;

        public Result(int numPassed, int numFailed, int numSkipped) {
            this.numPassed = numPassed;
            this.numFailed = numFailed;
            this.numSkipped = numSkipped;
        }

        public int numPassed() {
            return numPassed;
        }

        public int numFailed() {
            return numFailed;
        }

        public int numSkipped() {
            return numSkipped;
        }

        public int numTotal() {
            return numPassed + numFailed + numSkipped;
        }
    }

    String id();
    List<Build> builds();
    List<Test> tests();
    Status status();
    Result result();

    static enum State {
        COMPLETED,
        RUNNING,
        SCHEDULED
    }
    State state();
    default boolean isCompleted() {
        return state() == State.COMPLETED;
    }
    default boolean isRunning() {
        return state() == State.COMPLETED;
    }
    default boolean isScheduled() {
        return state() == State.SCHEDULED;
    }
}
