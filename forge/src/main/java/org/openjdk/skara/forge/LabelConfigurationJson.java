/*
 * Copyright (c) 2020, 2025, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.forge;

import org.openjdk.skara.json.*;

import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class LabelConfigurationJson implements LabelConfiguration {
    private final Map<String, List<Pattern>> matchers;
    private final Map<String, List<String>> groups;
    private final Set<String> extra;
    private final Set<String> allowed;

    private LabelConfigurationJson(Map<String, List<Pattern>> matchers, Map<String, List<String>> groups, Set<String> extra) {
        this.matchers = Collections.unmodifiableMap(matchers);
        this.groups = Collections.unmodifiableMap(groups);
        this.extra = Collections.unmodifiableSet(extra);

        var allowed = new HashSet<String>();
        allowed.addAll(matchers.keySet());
        allowed.addAll(groups.keySet());
        allowed.addAll(extra);
        this.allowed = Collections.unmodifiableSet(allowed);
    }

    public static class Builder {
        private final Map<String, List<Pattern>> matchers = new HashMap<>();
        private final Map<String, List<String>> groups = new HashMap<>();
        private final Set<String> extra = new HashSet<>();

        public Builder addMatchers(String label, List<Pattern> matchers) {
            this.matchers.put(label, matchers);
            return this;
        }

        public Builder addGroup(String label, List<String> members) {
            groups.put(label, members);
            return this;
        }

        public Builder addExtra(String label) {
            extra.add(label);
            return this;
        }

        public LabelConfiguration build() {
            return new LabelConfigurationJson(matchers, groups, extra);
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static LabelConfiguration from(JSONValue json) {
        var builder = builder();
        if (json.contains("matchers")) {
            var fields = json.get("matchers").fields();
            var matchers = fields.stream()
                                 .collect(Collectors.toMap(JSONObject.Field::name,
                                                           field -> field.value()
                                                                         .stream()
                                                                         .map(JSONValue::asString)
                                                                         .map(s -> Pattern.compile("^" + s, Pattern.CASE_INSENSITIVE))
                                                                         .collect(Collectors.toList())));
            matchers.forEach(builder::addMatchers);
        }
        if (json.contains("groups")) {
            var fields = json.get("groups").fields();
            var groups = fields.stream()
                               .collect(Collectors.toMap(JSONObject.Field::name,
                                                         field -> field.value()
                                                                       .stream()
                                                                       .map(JSONValue::asString)
                                                                       .collect(Collectors.toList())));
            groups.forEach(builder::addGroup);
        }
        if (json.contains("extra")) {
            var extra = json.get("extra").stream()
                            .map(JSONValue::asString)
                            .collect(Collectors.toList());
            extra.forEach(builder::addExtra);
        }
        return builder.build();
    }

    public static LabelConfiguration fromHostedRepositoryFile(HostedRepository repository, String ref, String filename) {
        var jsonText = repository.fileContents(filename, ref).orElseThrow(() ->
                new RuntimeException("Could not find " + filename + " on ref " + ref + " in repo" + repository.name())
        );
        var json = JSON.parse(jsonText);
        return from(json);
    }

    public Set<String> label(Set<Path> changes) {
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

        return upgradeLabelsToGroups(labels);
    }

    public Set<String> allowed() {
        return allowed;
    }

    public boolean isAllowed(String s) {
        return allowed.contains(s);
    }

    @Override
    public Set<String> upgradeLabelsToGroups(Set<String> labels) {
        var ret = new HashSet<>(labels);
        // If the current labels matches at least two members of a group, use the group
        for (var group : groups.entrySet()) {
            var count = 0;
            for (var groupEntry : group.getValue()) {
                if (ret.contains(groupEntry)) {
                    count++;
                    if (count == 2) {
                        ret.add(group.getKey());
                        break;
                    }
                }
            }
        }

        // Finally remove all group members for any group that has been matched (note that a group can
        // also have individual rules and be matched in the first step).
        for (var group : groups.entrySet()) {
            if (ret.contains(group.getKey())) {
                ret.removeAll(group.getValue());
            }
        }
        return ret;
    }

    public Optional<String> groupLabel(String label) {
        for (var group : groups.entrySet()) {
            if (group.getValue().contains(label)) {
                return Optional.of(group.getKey());
            }
        }
        return Optional.empty();
    }
}
