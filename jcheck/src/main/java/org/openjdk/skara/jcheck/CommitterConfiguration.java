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

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class CommitterConfiguration {
    static final CommitterConfiguration DEFAULT = new CommitterConfiguration("committer", Set.of());

    private final String role;
    private final Set<String> allowedToMerge;

    CommitterConfiguration(String role, Set<String> allowedToMerge) {
        this.role = role;
        this.allowedToMerge = allowedToMerge;
    }

    public String role() {
        return role;
    }

    public Set<String> allowedToMerge() {
        return allowedToMerge;
    }

    static String name() {
        return "committer";
    }

    static CommitterConfiguration parse(Section s) {
        if (s == null) {
            return DEFAULT;
        }

        var role = s.get("role", DEFAULT.role());
        var usernames = s.get("allowed-to-merge", "");
        var allowedToMerge = new HashSet<String>();
        for (var username : usernames.split(",")) {
            allowedToMerge.add(username.trim());
        }
        return new CommitterConfiguration(role, allowedToMerge);
    }
}

