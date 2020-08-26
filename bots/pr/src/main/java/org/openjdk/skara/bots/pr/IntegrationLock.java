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
package org.openjdk.skara.bots.pr;

import org.openjdk.skara.forge.PullRequest;

import java.time.Duration;
import java.util.concurrent.*;

public class IntegrationLock implements AutoCloseable {
    private static final ConcurrentHashMap<String, Semaphore> pendingIntegrations = new ConcurrentHashMap<>();

    static IntegrationLock create(PullRequest pr, Duration timeout) {
        var repoName = pr.repository().webUrl().toString();
        var repoPending = pendingIntegrations.computeIfAbsent(repoName, key -> new Semaphore(1));
        try {
            var locked = repoPending.tryAcquire(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return new IntegrationLock(locked ? repoPending : null);
        } catch (InterruptedException e) {
            return new IntegrationLock(null);
        }
    }

    private final Semaphore semaphore;

    private IntegrationLock(Semaphore semaphore) {
        this.semaphore = semaphore;
    }

    @Override
    public void close() {
        if (semaphore != null) {
            semaphore.release();
        }
    }

    public boolean isLocked() {
        return semaphore != null;
    }
}
