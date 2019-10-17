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
package org.openjdk.skara.bots.submit;

import org.openjdk.skara.forge.*;

import java.time.*;
import java.util.logging.Logger;

public class CheckUpdater implements Runnable {
    private final PullRequest pr;
    private final CheckBuilder checkBuilder;
    private Instant lastUpdate;
    private Duration maxUpdateRate;
    private final Logger log = Logger.getLogger("org.openjdk.skara.bots.submit");

    CheckUpdater(PullRequest pr, CheckBuilder checkBuilder) {
        this.pr = pr;
        this.checkBuilder = checkBuilder;

        lastUpdate = Instant.EPOCH;
        maxUpdateRate = Duration.ofSeconds(10);
    }

    void setMaxUpdateRate(Duration maxUpdateRate) {
        this.maxUpdateRate = maxUpdateRate;
    }

    @Override
    public void run() {
        if (Instant.now().isAfter(lastUpdate.plus(maxUpdateRate))) {
            pr.updateCheck(checkBuilder.build());
            lastUpdate = Instant.now();
        } else {
            log.finest("Rate limiting check updates");
        }
    }
}
