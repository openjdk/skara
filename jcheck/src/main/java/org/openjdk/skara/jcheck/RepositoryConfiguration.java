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
package org.openjdk.skara.jcheck;

import org.openjdk.skara.ini.Section;

public class RepositoryConfiguration {
    private static final RepositoryConfiguration DEFAULT =
        new RepositoryConfiguration(".*", ".*");

    private final String branches;
    private final String tags;

    RepositoryConfiguration(String branches, String tags) {
        this.branches = branches;
        this.tags = tags;
    }

    public String branches() {
        return branches;
    }

    public String tags() {
        return tags;
    }

    static String name() {
        return "repository";
    }

    static RepositoryConfiguration parse(Section s) {
        if (s == null) {
            return DEFAULT;
        }

        var branches = s.get("branches", DEFAULT.branches());
        var tags = s.get("tags", DEFAULT.tags());
        return new RepositoryConfiguration(branches, tags);
    }
}
