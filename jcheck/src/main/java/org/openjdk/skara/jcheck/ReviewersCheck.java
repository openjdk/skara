/*
 * Copyright (c) 2018, 2019, Oracle and/or its affiliates. All rights reserved.
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
import org.openjdk.skara.census.Project;
import org.openjdk.skara.vcs.Commit;
import org.openjdk.skara.vcs.openjdk.CommitMessage;

import java.io.IOException;
import java.util.Iterator;
import java.util.stream.Collectors;
import java.util.logging.Logger;

public class ReviewersCheck extends CommitCheck {
    private final Logger log = Logger.getLogger("org.openjdk.skara.jcheck.reviewers");
    private final Census census;
    private final Utilities utils;

    ReviewersCheck(Census census, Utilities utils) {
        this.census = census;
        this.utils = utils;
    }

    private boolean hasRole(Project project, String role, String username, int version) {
        switch (role) {
            case "lead":
                return project.isLead(username, version);
            case "reviewer":
                return project.isReviewer(username, version);
            case "committer":
                return project.isCommitter(username, version);
            case "author":
                return project.isAuthor(username, version);
            case "contributor":
                return census.isContributor(username);
            default:
                throw new IllegalStateException("Unsupported role: " + role);
        }
    }

    @Override
    Iterator<Issue> check(Commit commit, CommitMessage message, JCheckConfiguration conf) {
        if (commit.isMerge() || utils.addsHgTag(commit)) {
            return iterator();
        }

        var metadata = CommitIssue.metadata(commit, message, conf, this);
        var project = census.project(conf.general().project());
        var version = conf.census().version();
        var domain = conf.census().domain();
        var role = conf.checks().reviewers().role();
        var required = conf.checks().reviewers().minimum();
        var ignore = conf.checks().reviewers().ignore();
        var reviewers = message.reviewers()
                               .stream()
                               .filter(r -> !ignore.contains(r))
                               .collect(Collectors.toList());

        var actual = reviewers.stream()
                              .filter(reviewer -> hasRole(project, role, reviewer, version))
                              .count();
        if (actual < required) {
            log.finer("issue: too few reviewers found");
            return iterator(new TooFewReviewersIssue(Math.toIntExact(actual), required, metadata));
        }

        var invalid = reviewers.stream()
                               .filter(r -> !census.isContributor(r))
                               .collect(Collectors.toList());
        if (!reviewers.isEmpty() && !invalid.isEmpty()) {
            log.finer("issue: invalid reviewers found");
            return iterator(new InvalidReviewersIssue(invalid, project, metadata));
        }

        var username = commit.author().name();
        var email = commit.author().email();
        if (email != null && email.endsWith("@" + domain)) {
            username = email.split("@")[0];
        }
        if (reviewers.size() == 1 &&
            reviewers.get(0).equals(username) &&
            message.contributors().isEmpty()) {
            log.finer("issue: self-review");
            return iterator(new SelfReviewIssue(metadata));
        }

        return iterator();
    }

    @Override
    public String name() {
        return "reviewers";
    }

    @Override
    public String description() {
        return "Change must be properly reviewed";
    }
}
