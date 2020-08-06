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
package org.openjdk.skara.vcs.openjdk;

import org.openjdk.skara.vcs.Author;

import java.util.List;

public class CommitMessage {
    private final String title;
    private final List<Issue> issues;
    private final List<String> reviewers;
    private final List<Author> contributors;
    private final List<String> summaries;
    private final List<String> additional;

    public CommitMessage(String title,
                         List<Issue> issues,
                         List<String> reviewers,
                         List<Author> contributors,
                         List<String> summaries,
                         List<String> additional) {
        this.title = title;
        this.issues = issues;
        this.reviewers = reviewers;
        this.contributors = contributors;
        this.summaries = summaries;
        this.additional = additional;
    }

    public String title() {
        return title;
    }

    public List<Issue> issues() {
        return issues;
    }

    public List<String> reviewers() {
        return reviewers;
    }

    public List<Author> contributors() {
        return contributors;
    }

    public void addContributor(Author contributor) {
        contributors.add(contributor);
    }

    public List<String> summaries() {
        return summaries;
    }

    public List<String> additional() {
        return additional;
    }

    public static CommitMessageBuilder title(String title) {
        if (title == null || title.isEmpty()) {
            throw new IllegalArgumentException("Must provide a non-empty title");
        }

        var builder = new CommitMessageBuilder();
        builder.title(title);
        return builder;
    }

    public static CommitMessageBuilder title(Issue... issues) {
        var builder = new CommitMessageBuilder();
        builder.issues(issues);
        return builder;
    }

    public static CommitMessageBuilder title(List<Issue> issues) {
        var builder = new CommitMessageBuilder();
        builder.issues(issues);
        return builder;
    }

    public List<String> format(CommitMessageFormatter formatter) {
        return formatter.format(this);
    }
}
