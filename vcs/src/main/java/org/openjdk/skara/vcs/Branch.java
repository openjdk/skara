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
package org.openjdk.skara.vcs;

public class Branch {
    private static final Branch defaultGit = new Branch("master");
    private static final Branch defaultHg = new Branch("default");

    private final String name;

    public Branch(String name) {
        this.name = name;
    }

    public String name() {
        return this.name;
    }

    public static Branch defaultFor(VCS vcs) {
        if (vcs == VCS.GIT) {
            return defaultGit;
        }
        if (vcs == VCS.HG) {
            return defaultHg;
        }
        throw new IllegalArgumentException("Unsupported VCS: " + vcs);
    }

    @Override
    public String toString() {
        return name;
    }

    @Override
    public int hashCode() {
        return name.hashCode();
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Branch other)) {
            return false;
        }

        return name.equals(other.name);
    }
}
