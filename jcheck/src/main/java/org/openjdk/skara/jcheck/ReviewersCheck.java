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
import java.util.List;
import java.util.Map;
import java.util.HashMap;
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

    private String nextRequiredRole(String role, Map<String, Integer> count) {
        switch (role) {
            case "lead":
                return count.get("reviewer") != 0 ?
                    "reviewer" : nextRequiredRole("reviewer", count);
            case "reviewer":
                return count.get("committer") != 0 ?
                    "committer" : nextRequiredRole("committer", count);
            case "committer":
                return count.get("author") != 0 ?
                    "author" : nextRequiredRole("author", count);
            case "author":
                return count.get("contributor") != 0 ?
                    "contributor" : nextRequiredRole("contributor", count);
            case "contributor":
                return null;
            default:
                throw new IllegalArgumentException("Unexpected role: " + role);
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

        var numLeadRole = conf.checks().reviewers().lead();
        var numReviewerRole = conf.checks().reviewers().reviewers();
        var numCommitterRole = conf.checks().reviewers().committers();
        var numAuthorRole = conf.checks().reviewers().authors();
        var numContributorRole = conf.checks().reviewers().contributors();

        var ignore = conf.checks().reviewers().ignore();
        var reviewers = message.reviewers()
                               .stream()
                               .filter(r -> !ignore.contains(r))
                               .collect(Collectors.toList());

        var invalid = reviewers.stream()
                               .filter(r -> !census.isContributor(r))
                               .collect(Collectors.toList());
        if (!reviewers.isEmpty() && !invalid.isEmpty()) {
            log.finer("issue: invalid reviewers found");
            return iterator(new InvalidReviewersIssue(invalid, project, metadata));
        }

        var requirements = Map.of(
                "lead", numLeadRole,
                "reviewer", numReviewerRole,
                "committer", numCommitterRole,
                "author", numAuthorRole,
                "contributor", numContributorRole);

        var roles = new HashMap<String, String>();
        for (var reviewer : reviewers) {
            String role = null;
            if (project.isLead(reviewer, version)) {
                role = "lead";
            } else if (project.isReviewer(reviewer, version)) {
                role = "reviewer";
            } else if (project.isCommitter(reviewer, version)) {
                role = "committer";
            } else if (project.isAuthor(reviewer, version)) {
                role = "author";
            } else if (census.isContributor(reviewer)) {
                role = "contributor";
            } else {
                throw new IllegalStateException("No role for reviewer: " + reviewer);
            }

            roles.put(reviewer, role);
        }

        var missing = new HashMap<String, Integer>(requirements);
        for (var reviewer : reviewers) {
            var role = roles.get(reviewer);
            if (missing.get(role) == 0) {
                var next = nextRequiredRole(role, missing);
                if (next != null) {
                    missing.put(next, missing.get(next) - 1);
                }
            } else {
                missing.put(role, missing.get(role) - 1);
            }
        }

        for (var role : missing.keySet()) {
            int required = requirements.get(role);
            int n = missing.get(role);
            if (n > 0) {
                log.finer("issue: too few reviewers with role " + role + " found");
                return iterator(new TooFewReviewersIssue(required - n, required, role, metadata));
            }
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
