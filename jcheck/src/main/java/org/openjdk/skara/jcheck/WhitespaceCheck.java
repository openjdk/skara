/*
 * Copyright (c) 2018, 2020, Oracle and/or its affiliates. All rights reserved.
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
import org.openjdk.skara.vcs.openjdk.CommitMessage;

import java.util.*;
import java.util.regex.Pattern;
import java.util.logging.Logger;

public class WhitespaceCheck extends CommitCheck {
    private final Logger log = Logger.getLogger("org.openjdk.skara.jcheck.whitespace");

    @Override
    Iterator<Issue> check(Commit commit, CommitMessage message, JCheckConfiguration conf, Census census) {
        var metadata = CommitIssue.metadata(commit, message, conf, this);
        var issues = new ArrayList<Issue>();
        var pattern = Pattern.compile(conf.checks().whitespace().files());
        var tabPattern = Pattern.compile(conf.checks().whitespace().ignoreTabs());

        for (var diff : commit.parentDiffs()) {
            for (var patch : diff.patches()) {
                if (!patch.target().path().isPresent() || patch.isBinary()) {
                    continue;
                }
                var path = patch.target().path().get();
                if (pattern.matcher(path.toString()).matches()) {
                    for (var hunk : patch.asTextualPatch().hunks()) {
                        var lines = hunk.target().lines();
                        for (var i = 0; i < lines.size(); i++) {
                            var line = lines.get(i);
                            var row = hunk.target().range().start() + i;
                            var tabIndex = line.indexOf('\t');
                            var crIndex = line.indexOf('\r');
                            var ignoreTab = tabPattern.matcher(path.toString()).matches();
                            if ((tabIndex >= 0 && !ignoreTab) || crIndex >= 0
                                    || line.endsWith(" ") || line.endsWith("\t")) {
                                var errors = new ArrayList<WhitespaceIssue.Error>();
                                var trailing = true;
                                for (var index = line.length() - 1; index >= 0; index--) {
                                    if ((line.charAt(index) == ' ' || line.charAt(index) == '\t') && trailing) {
                                        errors.add(WhitespaceIssue.trailing(index));
                                    } else if (line.charAt(index) == '\t'  && !ignoreTab) {
                                        errors.add(WhitespaceIssue.tab(index));
                                    } else if (line.charAt(index) == '\r') {
                                        errors.add(WhitespaceIssue.cr(index));
                                    } else {
                                        trailing = false;
                                    }
                                }
                                Collections.reverse(errors);
                                issues.add(new WhitespaceIssue(path, line, row, errors, metadata));
                            }
                        }
                    }
                }
            }
        }

        return issues.iterator();
    }

    @Override
    public String name() {
        return "whitespace";
    }

    @Override
    public String description() {
        return "Change must not contain extraneous whitespace";
    }
}
