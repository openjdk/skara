/*
 * Copyright (c) 2020, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.bots.mlbridge;

import org.openjdk.skara.forge.*;

import java.time.*;
import java.util.*;
import java.util.logging.Logger;

public class CooldownQuarantine {
    private final Map<String, Instant> quarantineEnd = new HashMap<>();
    private final Logger log = Logger.getLogger("org.openjdk.skara.bots.mlbridge");

    enum Status {
        NOT_IN_QUARANTINE,
        IN_QUARANTINE,
        JUST_RELEASED
    }

    public synchronized Status status(PullRequest pr) {
        var uniqueId = pr.webUrl().toString();

        if (!quarantineEnd.containsKey(uniqueId)) {
            return Status.NOT_IN_QUARANTINE;
        }
        var end = quarantineEnd.get(uniqueId);
        if (end.isBefore(Instant.now())) {
            log.info("Released from cooldown quarantine: " + pr.repository().name() + "#" + pr.id());
            quarantineEnd.remove(uniqueId);
            return Status.JUST_RELEASED;
        }
        log.info("Quarantined due to cooldown: " + pr.repository().name() + "#" + pr.id());
        return Status.IN_QUARANTINE;
    }

    public synchronized void updateQuarantineEnd(PullRequest pr, Instant end) {
        var uniqueId = pr.webUrl().toString();
        var currentEnd = quarantineEnd.getOrDefault(uniqueId, Instant.now());
        if (end.isAfter(currentEnd)) {
            quarantineEnd.put(uniqueId, end);
        }
    }
}
