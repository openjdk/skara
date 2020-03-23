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
import java.util.Set;
import java.util.stream.Collectors;

public class IssuesConfiguration {
    static final IssuesConfiguration DEFAULT =
        new IssuesConfiguration("^(([A-Z][A-Z0-9]+-)?[0-9]+): (\\S.*)$", true);

    private final String pattern;
    private final boolean required;

    IssuesConfiguration(String pattern, boolean required) {
        this.pattern = pattern;
        this.required = required;
    }

    public String pattern() {
        return pattern;
    }

    public boolean required() {
        return required;
    }

    static String name() {
        return "issues";
    }

    static IssuesConfiguration parse(Section s) {
        if (s == null) {
            return DEFAULT;
        }

        var pattern = s.get("pattern", DEFAULT.pattern());
        var required = s.get("required", DEFAULT.required());
        return new IssuesConfiguration(pattern, required);
    }
}
