/*
 * Copyright (c) 2019, 2022, Oracle and/or its affiliates. All rights reserved.
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

package org.openjdk.skara.gradle.module;

import org.gradle.api.Project;
import org.gradle.api.provider.Property;

import java.util.*;

public class ModuleExtension {
    public static class OpensDirective {
        private final String packageName;
        private final Map<String, List<String>> map;

        public OpensDirective(String packageName, Map<String, List<String>> map) {
            this.packageName = packageName;
            this.map = map;
        }

        public void to(String moduleName) {
            if (!map.containsKey(packageName)) {
                map.put(packageName, new ArrayList<>());
            }

            map.get(packageName).add(moduleName);
        }
    }

    private final Property<String> name;

    private final List<String> requires = new ArrayList<>();
    private final Map<String, List<String>> opens = new TreeMap<>();

    public ModuleExtension(Project project) {
        name = project.getObjects().property(String.class);
    }

    public Map<String, List<String>> getOpens() {
        return opens;
    }

    public List<String> getRequires() {
        return requires;
    }

    public Property<String> getName() {
        return name;
    }

    public void requires(String moduleName) {
        requires.add(moduleName);
    }

    public OpensDirective opens(String packageName) {
        return new OpensDirective(packageName, opens);
    }
}
