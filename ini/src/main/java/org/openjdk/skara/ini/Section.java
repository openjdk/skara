/*
 * Copyright (c) 2018, 2022, Oracle and/or its affiliates. All rights reserved.
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
import java.util.stream.Collectors;

public class Section {
    public static class Entry {
        private final String key;
        private final INI.Value value;

        Entry(Map.Entry<String, INI.Value> e) {
            this.key = e.getKey();
            this.value = e.getValue();
        }

        public String key() {
            return key;
        }

        public INI.Value value() {
            return value;
        }

        @Override
        public String toString() {
            return key + " = " + value.toString();
        }
    }
    private final String name;
    private final Map<String, INI.Value> entries;
    private final Map<String, Section> subsections;

    public Section(String name) {
        this.name = name;
        this.entries = new HashMap<>();
        this.subsections = new HashMap<>();
    }

    public String name() {
        return name;
    }

    public Section subsection(String name) {
        return subsections.get(name);
    }

    public boolean hasSubsection(String name) {
        return subsections.containsKey(name);
    }

    public Collection<Section> subsections() {
        return subsections.values();
    }

    public INI.Value get(String key) {
        return entries.get(key);
    }

    public int get(String key, int fallback) {
        if (contains(key)) {
            return entries.get(key).asInt();
        }
        return fallback;
    }

    public double get(String key, double fallback) {
        if (contains(key)) {
            return entries.get(key).asDouble();
        }
        return fallback;
    }

    public String get(String key, String fallback) {
        if (contains(key)) {
            return entries.get(key).asString();
        }
        return fallback;
    }

    public boolean get(String key, boolean fallback) {
        if (contains(key)) {
            return entries.get(key).asBoolean();
        }
        return fallback;
    }

    public List<String> get(String key, List<String> fallback) {
        if (contains(key)) {
            return entries.get(key).asList();
        }
        return fallback;
    }

    public boolean contains(String key) {
        return entries.containsKey(key);
    }

    public List<Entry> entries() {
        return entries.entrySet()
                      .stream()
                      .map(Entry::new)
                      .collect(Collectors.toList());
    }

    void put(String key, String value) {
        entries.put(key, new INI.Value(value));
    }

    void addSubsection(Section subsection) {
        subsections.put(subsection.name(), subsection);
    }

    @Override
    public String toString() {
        var sb = new StringBuilder();

        sb.append("[");
        sb.append(name);
        sb.append("]\n");

        for (var entry : entries()) {
            sb.append(entry.toString());
            sb.append("\n");
        }

        for (var subsection : subsections()) {
            sb.append("[");
            sb.append(name);
            sb.append(" \"");
            sb.append(subsection.name());
            sb.append("\"]\n");

            for (var entry : subsection.entries()) {
                sb.append(entry.toString());
                sb.append("\n");
            }
        }

        return sb.toString();
    }
}
