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

import org.openjdk.skara.json.JSON;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

public class LabelConfigurationHostedRepository implements LabelConfiguration {
    private final HostedRepository repository;
    private final String ref;
    private final String filename;

    private String latestFileContents = "";
    private LabelConfiguration latestParsedConfiguration;

    private LabelConfigurationHostedRepository(HostedRepository repository, String ref, String filename) {
        this.repository = repository;
        this.ref = ref;
        this.filename = filename;
    }

    public static LabelConfiguration from(HostedRepository repository, String ref, String filename) {
        return new LabelConfigurationHostedRepository(repository, ref, filename);
    }

    private LabelConfiguration labelConfiguration() {
        var contents = repository.fileContents(filename, ref)
                .orElseThrow(() -> new RuntimeException("Could not find " + filename + " on ref " + ref + " in repo " + repository.name()));
        if (!contents.equals(latestFileContents)) {
            latestFileContents = contents;
            var json = JSON.parse(contents);
            latestParsedConfiguration = LabelConfigurationJson.from(json);
        }
        return latestParsedConfiguration;
    }

    @Override
    public Set<String> label(Set<Path> changes) {
        return labelConfiguration().label(changes);
    }

    @Override
    public Set<String> allowed() {
        return labelConfiguration().allowed();
    }

    @Override
    public boolean isAllowed(String s) {
        return labelConfiguration().isAllowed(s);
    }

    @Override
    public Set<String> upgradeLabelsToGroups(Set<String> labels) {
        return labelConfiguration().upgradeLabelsToGroups(labels);
    }

    @Override
    public Set<String> groupLabels(String label) {
        return labelConfiguration().groupLabels(label);
    }

    @Override
    public List<String> labelsInGroup(String label) {
        return labelConfiguration().labelsInGroup(label);
    }
}

