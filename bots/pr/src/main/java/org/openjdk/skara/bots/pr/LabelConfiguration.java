/*
 * Copyright (c) 2020, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.bots.pr;

import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

public class LabelConfiguration {
    private final Map<String, List<Pattern>> matchers;
    private final Map<String, List<String>> groups;
    private final Set<String> extra;
    private final Set<String> allowed;

    private LabelConfiguration(Map<String, List<Pattern>> matchers, Map<String, List<String>> groups, Set<String> extra) {
        this.matchers = Collections.unmodifiableMap(matchers);
        this.groups = Collections.unmodifiableMap(groups);
        this.extra = Collections.unmodifiableSet(extra);

        var allowed = new HashSet<String>();
        allowed.addAll(matchers.keySet());
        allowed.addAll(groups.keySet());
        allowed.addAll(extra);
        this.allowed = Collections.unmodifiableSet(allowed);
    }

    static class LabelConfigurationBuilder {
        private final Map<String, List<Pattern>> matchers = new HashMap<>();
        private final Map<String, List<String>> groups = new HashMap<>();
        private final Set<String> extra = new HashSet<>();

        public LabelConfigurationBuilder addMatchers(String label, List<Pattern> matchers) {
            this.matchers.put(label, matchers);
            return this;
        }

        public LabelConfigurationBuilder addGroup(String label, List<String> members) {
            groups.put(label, members);
            return this;
        }

        public LabelConfigurationBuilder addExtra(String label) {
            extra.add(label);
            return this;
        }

        public LabelConfiguration build() {
            return new LabelConfiguration(matchers, groups, extra);
        }
    }

    static LabelConfigurationBuilder newBuilder() {
        return new LabelConfigurationBuilder();
    }

    public Set<String> fromChanges(Set<Path> changes) {
        var labels = new HashSet<String>();
        for (var file : changes) {
            for (var label : matchers.entrySet()) {
                for (var pattern : label.getValue()) {
                    var matcher = pattern.matcher(file.toString());
                    if (matcher.find()) {
                        labels.add(label.getKey());
                        break;
                    }
                }
            }
        }

        // If the current labels matches at least two members of a group, the group is also included
        for (var group : groups.entrySet()) {
            var count = 0;
            for (var groupEntry : group.getValue()) {
                if (labels.contains(groupEntry)) {
                    count++;
                    if (count == 2) {
                        labels.add(group.getKey());
                        break;
                    }
                }
            }
        }

        return labels;
    }

    public Set<String> allowed() {
        return allowed;
    }
}
