/*
 * Copyright (c) 2024, Oracle and/or its affiliates. All rights reserved.
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

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class CopyrightFormatConfiguration {

    /**
     * Configuration for a copyright check
     *
     * @param name      The Name for the copyright check.
     * @param locator   A Regex used to locate the copyright line.
     * @param validator A Regex used to validate the copyright line.
     * @param required  Indicates whether a copyright is required for each file; if true, the check will fail if the copyright is missing.
     */
    public record CopyrightConfiguration(String name, Pattern locator, Pattern validator, boolean required) {
    }

    private final String files;
    private final List<CopyrightConfiguration> copyrightConfigs;

    CopyrightFormatConfiguration(String files, List<CopyrightConfiguration> copyrightConfigs) {
        this.files = files;
        this.copyrightConfigs = copyrightConfigs;
    }

    public String files() {
        return files;
    }

    public List<CopyrightConfiguration> copyrightConfigs() {
        return copyrightConfigs;
    }

    static String name() {
        return "copyright";
    }

    static CopyrightFormatConfiguration parse(Section s) {
        if (s == null) {
            return null;
        }

        var files = s.get("files").asString();
        var configurations = new ArrayList<CopyrightConfiguration>();
        for (var entry : s.entries()) {
            var key = entry.key();
            var value = entry.value();
            if (key.contains("locator")) {
                var name = key.split("_")[0];
                var locator = Pattern.compile(value.asString());
                var validator = Pattern.compile(s.get(name + "_validator", ""));
                var required = s.get(name + "_required", false);
                configurations.add(new CopyrightConfiguration(name, locator, validator, required));
            }
        }
        return new CopyrightFormatConfiguration(files, configurations);
    }
}
