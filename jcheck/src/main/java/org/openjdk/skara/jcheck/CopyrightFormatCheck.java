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

import org.openjdk.skara.census.Census;
import org.openjdk.skara.vcs.Commit;
import org.openjdk.skara.vcs.ReadOnlyRepository;
import org.openjdk.skara.vcs.openjdk.CommitMessage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Pattern;

public class CopyrightFormatCheck extends CommitCheck {

    private final ReadOnlyRepository repo;

    CopyrightFormatCheck(ReadOnlyRepository repo) {
        this.repo = repo;
    }

    @Override
    Iterator<Issue> check(Commit commit, CommitMessage message, JCheckConfiguration conf, Census census) {
        var metadata = CommitIssue.metadata(commit, message, conf, this);
        var copyrightConf = conf.checks().copyright();
        if (copyrightConf == null) {
            return iterator();
        }
        var filesPattern = Pattern.compile(copyrightConf.files());
        var copyrightConfigs = copyrightConf.copyrightConfigs();
        var filesWithCopyrightFormatIssue = new HashMap<String, List<String>>();
        var filesWithCopyrightMissingIssue = new HashMap<String, List<String>>();

        for (var diff : commit.parentDiffs()) {
            for (var patch : diff.patches()) {
                if (patch.target().path().isEmpty() || patch.isBinary()) {
                    continue;
                }
                var path = patch.target().path().get();
                // Check if we need to check copyright in this type of file
                if (filesPattern.matcher(path.toString()).matches()) {
                    try {
                        var lines = repo.lines(path, commit.hash()).orElse(List.of());
                        // Iterate over every kind of configured copyright
                        for (var singleConf : copyrightConfigs) {
                            var copyrightFound = false;
                            for (String line : lines) {
                                if (singleConf.locator().matcher(line).matches()) {
                                    copyrightFound = true;
                                    if (!singleConf.validator().matcher(line).matches()) {
                                        filesWithCopyrightFormatIssue
                                                .computeIfAbsent(singleConf.name(), k -> new ArrayList<>())
                                                .add(path.toString());
                                    }
                                    break;
                                }
                            }
                            if (singleConf.required() && !copyrightFound) {
                                filesWithCopyrightMissingIssue
                                        .computeIfAbsent(singleConf.name(), k -> new ArrayList<>())
                                        .add(path.toString());
                            }
                        }
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }
            }
        }

        if (!filesWithCopyrightFormatIssue.isEmpty() || !filesWithCopyrightMissingIssue.isEmpty()) {
            return iterator(new CopyrightFormatIssue(metadata, filesWithCopyrightFormatIssue, filesWithCopyrightMissingIssue));
        }

        return iterator();
    }

    @Override
    public String name() {
        return "copyright";
    }

    @Override
    public String description() {
        return "Copyright should be properly formatted";
    }
}
