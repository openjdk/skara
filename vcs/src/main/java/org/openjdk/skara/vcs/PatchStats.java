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

public class PatchStats {
    private final Path path;
    private final int added;
    private final int removed;
    private final int modified;

    public PatchStats(Path path, int added, int removed, int modified) {
        this.path = path;
        this.added = added;
        this.removed = removed;
        this.modified = modified;
    }

    public Path path() {
        return path;
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
        return Objects.hash(path, added, removed, modified);
    }

    @Override
    public boolean equals(Object other) {
        if (other == this) {
            return true;
        }

        if (!(other instanceof PatchStats)) {
            return false;
        }

        var o = (PatchStats) other;
        return Objects.equals(path, o.path) &&
               Objects.equals(added, o.added) &&
               Objects.equals(removed, o.removed) &&
               Objects.equals(modified, o.modified);
    }
}
