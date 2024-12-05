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
import java.util.ArrayList;
import java.util.Iterator;
import java.time.Year;
import java.util.List;
import java.util.regex.Pattern;

public class CopyrightCheck extends CommitCheck {

    private final ReadOnlyRepository repo;

    private final static Pattern COPYRIGHT_PATTERN = Pattern.compile(".*Copyright \\(c\\) (\\d{4})(?:, (\\d{4}))?, Oracle and/or its affiliates\\. All rights reserved\\.");

    CopyrightCheck(ReadOnlyRepository repo) {
        this.repo = repo;
    }

    @Override
    Iterator<Issue> check(Commit commit, CommitMessage message, JCheckConfiguration conf, Census census) {
        var metadata = CommitIssue.metadata(commit, message, conf, this);
        var pattern = Pattern.compile(conf.checks().copyright().files());

        var filesWithCopyrightFormatIssue = new ArrayList<String>();
        var filesWithCopyrightYearIssue = new ArrayList<String>();
        var filesWithCopyrightMissingIssue = new ArrayList<String>();

        for (var diff : commit.parentDiffs()) {
            for (var patch : diff.patches()) {
                if (patch.target().path().isEmpty() || patch.isBinary()) {
                    continue;
                }
                var path = patch.target().path().get();
                if (pattern.matcher(path.toString()).matches()) {
                    try {
                        var lines = repo.lines(path, commit.hash()).orElse(List.of());
                        var copyrightFound = false;
                        for (String line : lines) {
                            if (line.contains("Copyright (c)") && line.contains("Oracle")) {
                                copyrightFound = true;
                                var matcher = COPYRIGHT_PATTERN.matcher(line);
                                if (matcher.matches()) {
                                    int minYear = Integer.parseInt(matcher.group(1));
                                    int maxYear = matcher.group(2) != null ? Integer.parseInt(matcher.group(2)) : minYear;
                                    int currentYear = Year.now().getValue();
                                    if (currentYear != maxYear) {
                                        filesWithCopyrightYearIssue.add(path.toString());
                                    }
                                } else {
                                    filesWithCopyrightFormatIssue.add(path.toString());
                                }
                            }
                        }
                        if (!copyrightFound) {
                            filesWithCopyrightMissingIssue.add(path.toString());
                        }
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
        }

        if (!filesWithCopyrightFormatIssue.isEmpty() || !filesWithCopyrightYearIssue.isEmpty() || !filesWithCopyrightMissingIssue.isEmpty()) {
            return iterator(new CopyrightIssue(metadata, filesWithCopyrightFormatIssue, filesWithCopyrightYearIssue, filesWithCopyrightMissingIssue));
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
