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
package org.openjdk.skara.vcs;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

public class WebrevStats {
    private final int added;
    private final int removed;
    private final int modified;

    public WebrevStats(int added, int removed, int modified) {
        this.added = added;
        this.removed = removed;
        this.modified = modified;
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

    @Override
    public int hashCode() {
        return Objects.hash(added, removed, modified);
    }

    @Override
    public boolean equals(Object other) {
        if (other == this) {
            return true;
        }

        if (!(other instanceof WebrevStats)) {
            return false;
        }

        var o = (WebrevStats) other;
        return Objects.equals(added, o.added) &&
               Objects.equals(removed, o.removed) &&
               Objects.equals(modified, o.modified);
    }
}
