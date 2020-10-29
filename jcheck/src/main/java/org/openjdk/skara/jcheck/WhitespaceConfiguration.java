/*
 * Copyright (c) 2019, 2020, Oracle and/or its affiliates. All rights reserved.
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

public class WhitespaceConfiguration {
    static final WhitespaceConfiguration DEFAULT =
        new WhitespaceConfiguration(".*\\.cpp|.*\\.hpp|.*\\.c|.*\\.h|.*\\.java", "");

    private final String files;
    private final String tabfiles;

    WhitespaceConfiguration(String files, String tabfiles) {
        this.files = files;
        this.tabfiles = tabfiles;
    }

    public String files() {
        return files;
    }

    public String tabfiles() {
        return tabfiles;
    }

    static String name() {
        return "whitespace";
    }

    static WhitespaceConfiguration parse(Section s) {
        if (s == null) {
            return DEFAULT;
        }

        var files = s.get("files", DEFAULT.files());
        var tabfiles = s.get("tabfiles", DEFAULT.tabfiles());
        return new WhitespaceConfiguration(files, tabfiles);
    }
}
