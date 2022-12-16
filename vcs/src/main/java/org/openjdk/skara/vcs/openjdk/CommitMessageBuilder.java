/*
 * Copyright (c) 2019, 2022, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.vcs.openjdk;

import org.openjdk.skara.vcs.Author;
import org.openjdk.skara.vcs.Hash;

import java.util.*;
import java.util.stream.Collectors;

public class CommitMessageBuilder {
    private String title = null;
    private Hash original = null;
    private List<Issue> issues = new ArrayList<>();
    private List<String> summaries = new ArrayList<>();
    private List<String> reviewers = new ArrayList<>();
    private List<Author> contributors = new ArrayList<>();

    CommitMessageBuilder() {
    }

    CommitMessageBuilder title(String title) {
        this.title = title;
        return this;
    }

    public CommitMessageBuilder issues(Issue... issues) {
        for (var issue : issues) {
            this.issues.add(issue);
        }
        return this;
    }

    public CommitMessageBuilder issues(List<Issue> issues) {
        this.issues.addAll(issues);
        return this;
    }

    public CommitMessageBuilder issue(Issue issue) {
        issues.add(issue);
        return this;
    }

    public CommitMessageBuilder summaries(List<String> summaries) {
        this.summaries.addAll(summaries);
        return this;
    }

    public CommitMessageBuilder summaries(String... summaries) {
        for (var summary : summaries) {
            this.summaries.add(summary);
        }
        return this;
    }

    public CommitMessageBuilder summary(String summary) {
        summaries.add(summary);
        return this;
    }

    public CommitMessageBuilder reviewers(List<String> reviewers) {
        this.reviewers.addAll(reviewers);
        return this;
    }

    public CommitMessageBuilder reviewers(String... reviewers) {
        for (var reviewer : reviewers) {
            this.reviewers.add(reviewer);
        }
        return this;
    }

    public CommitMessageBuilder reviewer(String reviewer) {
        reviewers.add(reviewer);
        return this;
    }

    public CommitMessageBuilder contributors(List<Author> contributors) {
        this.contributors.addAll(contributors);
        return this;
    }

    public CommitMessageBuilder contributors(Author... contributors) {
        for (var contributor : contributors) {
            this.contributors.add(contributor);
        }
        return this;
    }

    public CommitMessageBuilder original(Hash original) {
        this.original = original;
        return this;
    }

    public CommitMessageBuilder contributor(Author contributor) {
        contributors.add(contributor);
        return this;
    }

    public CommitMessage create() {
        return new CommitMessage(title, issues, reviewers, contributors, summaries, original, List.of());
    }

    public List<String> format(CommitMessageFormatter formatter) {
        return create().format(formatter);
    }
}
