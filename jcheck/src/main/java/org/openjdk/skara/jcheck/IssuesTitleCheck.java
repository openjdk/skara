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
import org.openjdk.skara.vcs.openjdk.CommitMessage;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Logger;
import java.util.regex.Pattern;

public class IssuesTitleCheck extends CommitCheck {
    private final Logger log = Logger.getLogger("org.openjdk.skara.jcheck.issuesTitle");
    private final static List<String> VALID_WORD_WITH_TRAILING_PERIOD = List.of("et al.", "etc.", "...");
    private final static Pattern FIRST_WORD_ALL_LOWER_CASE_PATTERN = Pattern.compile("[a-z]+(?:\\h.*)?");

    @Override
    Iterator<Issue> check(Commit commit, CommitMessage message, JCheckConfiguration conf, Census census) {

        var metadata = CommitIssue.metadata(commit, message, conf, this);

        // if issues check is not required, skip issuesTitleCheck
        if (conf.checks().issues().required() &&
                (commit.message().isEmpty() || message.issues().isEmpty())) {
            return iterator();
        }

        var issuesWithTrailingPeriod = new ArrayList<String>();
        var issuesWithLeadingLowerCaseLetter = new ArrayList<String>();

        for (var issue : message.issues()) {
            if (hasTrailingPeriod(issue.description())) {
                issuesWithTrailingPeriod.add("`" + issue + "`");
            }
            if (FIRST_WORD_ALL_LOWER_CASE_PATTERN.matcher(issue.description()).matches()) {
                issuesWithLeadingLowerCaseLetter.add("`" + issue + "`");
            }
        }
        if (!issuesWithTrailingPeriod.isEmpty() || !issuesWithLeadingLowerCaseLetter.isEmpty()) {
            return iterator(new IssuesTitleIssue(metadata, issuesWithTrailingPeriod, issuesWithLeadingLowerCaseLetter));
        }
        return iterator();
    }

    @Override
    public String name() {
        return "issuestitle";
    }

    @Override
    public String description() {
        return "Issue's title should be properly formatted";
    }

    private boolean hasTrailingPeriod(String description) {
        if (!description.endsWith(".")) {
            return false;
        }
        for (String phrase : VALID_WORD_WITH_TRAILING_PERIOD) {
            if (description.endsWith(phrase)) {
                return false;
            }
        }
        return true;
    }
}
