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

import java.util.List;
import java.util.stream.Collectors;

public class ReviewersConfiguration {
    static final ReviewersConfiguration DEFAULT = new ReviewersConfiguration(1, "reviewer", List.of("duke"));

    private final int minimum;
    private final String role;
    private final List<String> ignore;

    ReviewersConfiguration(int minimum, String role, List<String> ignore) {
        this.minimum = minimum;
        this.role = role;
        this.ignore = ignore;
    }

    public int minimum() {
        return minimum;
    }

    public String role() {
        return role;
    }

    public List<String> ignore() {
        return ignore;
    }

    static String name() {
        return "reviewers";
    }

    static ReviewersConfiguration parse(Section s) {
        if (s == null) {
            return DEFAULT;
        }

        var minimum = s.get("minimum", DEFAULT.minimum());
        var role = s.get("role", DEFAULT.role());
        var ignore = s.get("ignore", DEFAULT.ignore());
        return new ReviewersConfiguration(minimum, role, ignore);
    }
}
