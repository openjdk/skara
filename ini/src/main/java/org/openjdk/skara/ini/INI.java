/*
 * Copyright (c) 2018, 2019, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.ini;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

public class INI {
    public static class Value {
        private final String value;

        Value(String value) {
            this.value = value;
        }

        public int asInt() {
            return Integer.parseInt(value);
        }

        public double asDouble() {
            return Double.parseDouble(value);
        }

        public boolean asBoolean() {
            return Boolean.parseBoolean(value);
        }

        public String asString() {
            return value;
        }

        public List<String> asList() {
            return asList(Function.identity());
        }

        public <R> List<R> asList(Function <String, ? extends R> mapper) {
            return Arrays.asList(value.split(","))
                         .stream()
                         .map(String::trim)
                         .map(mapper)
                         .collect(Collectors.toList());
        }

        public <R> R as(Function <String, ? extends R> mapper) {
            return mapper.apply(value);
        }

        @Override
        public String toString() {
            return value;
        }
    }

    private final Map<String, Section> sections;
    private final Map<String, Value> entries;

    INI(Map<String, Section> sections, Map<String, Value> entries) {
        this.sections = sections;
        this.entries = entries;
    }

    public Collection<Section> sections() {
        return sections.values();
    }

    public Section section(String name) {
        return sections.get(name);
    }

    public boolean hasSection(String name) {
        return sections.containsKey(name);
    }

    public List<Section.Entry> entries() {
        return entries.entrySet()
                      .stream()
                      .map(Section.Entry::new)
                      .collect(Collectors.toList());
    }

    public Value get(String key) {
        return entries.get(key);
    }

    public boolean contains(String key) {
        return entries.containsKey(key);
    }

    @Override
    public String toString() {
        var sb = new StringBuilder();

        for (var entry : entries()) {
            sb.append(entry.toString());
            sb.append("\n");
        }

        for (var section : sections()) {
            sb.append(section.toString());
            sb.append("\n");
        }

        return sb.toString();
    }

    private static void fail(int line,String message) {
        var m = String.format("line %d: %s", line, message);
        throw new IllegalArgumentException(m);
    }

    public static INI parse(String s) {
        return parse(Arrays.asList(s.split("\n")));
    }

    public static INI parse(List<String> lines) {
        var globalEntries = new HashMap<String, Value>();
        var sections = new HashMap<String, Section>();

        Section current = null;
        for (var i = 0; i < lines.size(); i++) {
            var line = lines.get(i);
            if (line.isEmpty() || line.startsWith(";")) {
                continue;
            }

            if (line.startsWith("[")) {
                if (!line.endsWith("]")) {
                    fail(i, "section header must end with ']'");
                }

                var content = line.substring(1, line.length() - 1); 
                var parts = content.split(" ");
                if (parts.length > 2) {
                    fail(i, "section header must be of format '[name (\"subsection\")?]'");
                }

                var name = parts[0];
                if (parts.length == 1) {
                    if (sections.containsKey(name)) {
                        current = sections.get(name);
                    } else {
                        current = new Section(name);
                        sections.put(current.name(), current);
                    }
                } else {
                    var subsection = parts[1];
                    if (!(subsection.startsWith("\"") && subsection.endsWith("\""))) {
                        fail(i, "section header must be of format '[name (\"subsection\")?]'");
                    }

                    var subsectionName = subsection.substring(1, subsection.length() - 1);
                    if (subsectionName.equals("")) {
                        fail(i, "subsection must have a name");
                    }

                    if (!sections.containsKey(name)) {
                        fail(i, "subsection to an unknown section '" + name + "'");
                    }

                    var section = sections.get(name);
                    if (section.hasSubsection(subsectionName)) {
                        current = section.subsection(subsectionName);
                    } else {
                        current = new Section(subsectionName);
                        section.addSubsection(current);
                    }
                }
            } else {
                if (!line.contains("=")) {
                    fail(i, "entry must be of form 'key = value'");
                }
                var splitIndex = line.indexOf("=");
                var key = line.substring(0, splitIndex).trim();
                var value = line.substring(splitIndex + 1, line.length()).trim();

                if (current == null) {
                    globalEntries.put(key, new Value(value));
                } else {
                    current.put(key, value);
                }
            }
        }

        return new INI(sections, globalEntries);
    }
}
