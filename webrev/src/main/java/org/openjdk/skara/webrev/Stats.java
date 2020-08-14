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
package org.openjdk.skara.webrev;

import org.openjdk.skara.vcs.WebrevStats;

class Stats {
    private final int added;
    private final int removed;
    private final int modified;
    private final int total;

    public Stats(WebrevStats stats, int total) {
        this.added = stats.added();
        this.removed = stats.removed();
        this.modified = stats.modified();
        this.total = total;
    }

    public Stats(int added, int removed, int modified, int total) {
        this.added = added;
        this.removed = removed;
        this.modified = modified;
        this.total = total;
    }

    public static Stats empty() {
        return new Stats(0, 0, 0, 0);
    }

    public int added() {
        return added;
    }

    public int removed() {
        return removed;
    }

    public int modified() {
        return modified;
    }

    public int changed() {
        return added() + removed() + modified();
    }

    public int unchanged() {
        return total() - changed();
    }

    public int total() {
        return total;
    }

    @Override
    public String toString() {
        return String.format("%d lines changed; %d ins; %d del; %d mod; %d unchg",
                             changed(), added(), removed(), modified(), unchanged());
    }
}
