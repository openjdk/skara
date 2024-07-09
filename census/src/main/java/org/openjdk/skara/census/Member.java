/*
 * Copyright (c) 2018, 2024, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.census;

import java.util.Objects;
import java.util.Optional;

class Member {
    private final Contributor contributor;
    private final int since;
    private final int until;

    Member(Contributor contributor) {
        this(contributor, 0);
    }

    Member(Contributor contributor, int since) {
        this(contributor, since, Integer.MAX_VALUE);
    }


    Member(Contributor contributor, int since, int until) {
        this.contributor = contributor;
        this.since = since;
        this.until = until;
    }

    public String username() {
        return contributor.username();
    }

    public Optional<String> fullName() {
        return contributor.fullName();
    }

    public Contributor contributor() {
        return contributor;
    }

    public int since() {
        return since;
    }

    public int until() {
        return until;
    }

    @Override
    public String toString() {
        if (until == Integer.MAX_VALUE) {
            return String.format("%s [%d,)", contributor, since);
        }
        return String.format("%s [%d,%d)", contributor, since, until);
    }

    @Override
    public int hashCode() {
        return Objects.hash(contributor, since, until);
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof Member other) {
            return contributor.equals(other.contributor()) &&
                   since == other.since() &&
                   until == other.until();
        }
        return false;
    }
}
