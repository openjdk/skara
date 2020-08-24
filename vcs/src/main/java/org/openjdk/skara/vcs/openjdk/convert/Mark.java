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
package org.openjdk.skara.vcs.openjdk.convert;

import org.openjdk.skara.vcs.Hash;
import java.util.Objects;
import java.util.Optional;
import static java.util.Objects.equals;

public class Mark implements Comparable<Mark> {
    private final int key;
    private final Hash hg;
    private final Hash git;
    private final Hash tag;

    public Mark(int key, Hash hg, Hash git) {
        if (key == 0) {
            throw new IllegalArgumentException("A mark cannot be 0");
        }
        this.key = key;
        this.hg = hg;
        this.git = git;
        this.tag = null;
    }

    public Mark(int key, Hash hg, Hash git, Hash tag) {
        if (key == 0) {
            throw new IllegalArgumentException("A mark cannot be 0");
        }
        this.key = key;
        this.hg = hg;
        this.git = git;
        this.tag = tag;
    }

    public int key() {
        return key;
    }

    public Hash hg() {
        return hg;
    }

    public Hash git() {
        return git;
    }

    public Optional<Hash> tag() {
        return Optional.ofNullable(tag);
    }

    @Override
    public int compareTo(Mark o) {
        return Integer.compare(key, o.key);
    }

    @Override
    public int hashCode() {
        return Objects.hash(key, hg, git, tag);
    }

    @Override
    public boolean equals(Object o) {
        if (o == this) {
            return true;
        }

        if (o instanceof Mark) {
            var m = (Mark) o;
            return Objects.equals(key, m.key) &&
                   Objects.equals(hg, m.hg) &&
                   Objects.equals(git, m.git) &&
                   Objects.equals(tag, m.tag);
        }

        return false;
    }

    @Override
    public String toString() {
        return hg.hex() + " " + git.hex();
    }
}
