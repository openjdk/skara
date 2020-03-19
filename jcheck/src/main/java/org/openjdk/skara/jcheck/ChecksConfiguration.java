/*
 * Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
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

import org.openjdk.skara.ini.Section;

import java.util.List;
import java.util.stream.Collectors;

public class ChecksConfiguration {
    private static ChecksConfiguration DEFAULT =
        new ChecksConfiguration(List.of(),
                                List.of(),
                                WhitespaceConfiguration.DEFAULT,
                                ReviewersConfiguration.DEFAULT,
                                MergeConfiguration.DEFAULT,
                                CommitterConfiguration.DEFAULT,
                                IssuesConfiguration.DEFAULT,
                                ProblemListsConfiguration.DEFAULT);

    private final List<String> error;
    private final List<String> warning;
    private final WhitespaceConfiguration whitespace;
    private final ReviewersConfiguration reviewers;
    private final MergeConfiguration merge;
    private final CommitterConfiguration committer;
    private final IssuesConfiguration issues;
    private final ProblemListsConfiguration problemlists;

    ChecksConfiguration(List<String> error,
                        List<String> warning,
                        WhitespaceConfiguration whitespace,
                        ReviewersConfiguration reviewers,
                        MergeConfiguration merge,
                        CommitterConfiguration committer,
                        IssuesConfiguration issues,
                        ProblemListsConfiguration problemlists) {
        this.error = error;
        this.warning = warning;
        this.whitespace = whitespace;
        this.reviewers = reviewers;
        this.merge = merge;
        this.committer = committer;
        this.issues = issues;
        this.problemlists = problemlists;
    }

    public List<String> error() {
        return error;
    }

    public List<String> warning() {
        return warning;
    }

    public Severity severity(String name) {
        if (error.contains(name)) {
            return Severity.ERROR;
        }

        if (warning.contains(name)) {
            return Severity.WARNING;
        }

        return null;
    }

    public List<CommitCheck> enabled(List<CommitCheck> available) {
        return available.stream()
                        .filter(c -> error.contains(c.name()) || warning.contains(c.name()))
                        .collect(Collectors.toList());
    }

    public WhitespaceConfiguration whitespace() {
        return whitespace;
    }

    public ReviewersConfiguration reviewers() {
        return reviewers;
    }

    public MergeConfiguration merge() {
        return merge;
    }

    public CommitterConfiguration committer() {
        return committer;
    }

    public IssuesConfiguration issues() {
        return issues;
    }

    public ProblemListsConfiguration problemlists() {
        return problemlists;
    }

    static String name() {
        return "checks";
    }

    static ChecksConfiguration parse(Section s) {
        if (s == null) {
            return DEFAULT;
        }

        var error = s.get("error", DEFAULT.error());
        var warning = s.get("warning", DEFAULT.warning());

        var whitespace = WhitespaceConfiguration.parse(s.subsection(WhitespaceConfiguration.name()));
        var reviewers = ReviewersConfiguration.parse(s.subsection(ReviewersConfiguration.name()));
        var merge = MergeConfiguration.parse(s.subsection(MergeConfiguration.name()));
        var committer = CommitterConfiguration.parse(s.subsection(CommitterConfiguration.name()));
        var issues = IssuesConfiguration.parse(s.subsection(IssuesConfiguration.name()));
        var problemlists = ProblemListsConfiguration.parse(s.subsection(ProblemListsConfiguration.name()));
        return new ChecksConfiguration(error, warning, whitespace, reviewers, merge, committer, issues, problemlists);
    }
}
