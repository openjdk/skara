/*
 * Copyright (c) 2019, 2020, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.cli.pr;

import org.openjdk.skara.forge.PullRequestBody;
import org.openjdk.skara.args.*;

import static org.openjdk.skara.cli.pr.Utils.*;

import java.io.IOException;
import java.nio.file.Path;
import java.net.URI;
import java.util.*;
import java.util.stream.Collectors;

public class GitPrInfo {
    static final List<Flag> flags = List.of(
        Option.shortcut("u")
              .fullname("username")
              .describe("NAME")
              .helptext("Username on host")
              .optional(),
        Option.shortcut("r")
              .fullname("remote")
              .describe("NAME")
              .helptext("Name of remote, defaults to 'origin'")
              .optional(),
        Switch.shortcut("")
              .fullname("no-decoration")
              .helptext("Hide any decorations when listing PRs")
              .optional(),
        Switch.shortcut("")
              .fullname("no-token")
              .helptext("Do not use a personal access token (PAT)")
              .optional(),
        Switch.shortcut("")
              .fullname("checks")
              .helptext("Show information about checks")
              .optional(),
        Switch.shortcut("")
              .fullname("author")
              .helptext("Show the author of the pull request")
              .optional(),
        Switch.shortcut("")
              .fullname("title")
              .helptext("Show the title of the pull request")
              .optional(),
        Switch.shortcut("")
              .fullname("assignees")
              .helptext("Show the assignees of the pull request")
              .optional(),
        Switch.shortcut("")
              .fullname("reviewers")
              .helptext("Show the reviewers of the pull request")
              .optional(),
        Switch.shortcut("")
              .fullname("contributors")
              .helptext("Show the additional contributors to the pull request")
              .optional(),
        Switch.shortcut("")
              .fullname("issues")
              .helptext("Show the issues associated with the pull request")
              .optional(),
        Switch.shortcut("")
              .fullname("commits")
              .helptext("Show the commits in pull request")
              .optional(),
        Switch.shortcut("")
              .fullname("branch")
              .helptext("Show the target branch for the pull request")
              .optional(),
        Switch.shortcut("")
              .fullname("url")
              .helptext("Show the url for the pull request")
              .optional(),
        Switch.shortcut("")
              .fullname("status")
              .helptext("Show the status for the pull request")
              .optional(),
        Switch.shortcut("")
              .fullname("labels")
              .helptext("Show the labels for the pull request")
              .optional(),
        Switch.shortcut("")
              .fullname("verbose")
              .helptext("Turn on verbose output")
              .optional(),
        Switch.shortcut("")
              .fullname("debug")
              .helptext("Turn on debugging output")
              .optional(),
        Switch.shortcut("")
              .fullname("version")
              .helptext("Print the version of this tool")
              .optional()
    );

    static final List<Input> inputs = List.of(
        Input.position(0)
             .describe("ID")
             .singular()
             .optional()
    );

    public static void main(String[] args) throws IOException {
        var parser = new ArgumentParser("git-pr", flags, inputs);
        var arguments = parse(parser, args);
        var repo = getRepo();
        var uri = getURI(repo, arguments);
        var host = getForge(uri, repo, arguments);
        var id = pullRequestIdArgument(repo, arguments);
        var pr = getPullRequest(uri, repo, host, id);
        var body = PullRequestBody.parse(pr);

        var showDecoration = !getSwitch("no-decoration", "info", arguments);
        var showChecks = getSwitch("checks", "info", arguments);
        var showTitle = getSwitch("title", "info", arguments);
        var showUrl = getSwitch("url", "info", arguments);
        var showLabels = getSwitch("labels", "info", arguments);
        var showAssignees = getSwitch("assignees", "info", arguments);
        var showReviewers = getSwitch("reviewers", "info", arguments);
        var showBranch = getSwitch("branch", "info", arguments);
        var showCommits = getSwitch("commits", "info", arguments);
        var showAuthor = getSwitch("author", "info", arguments);
        var showStatus = getSwitch("status", "info", arguments);
        var showIssues = getSwitch("issues", "info", arguments);
        var showContributors = getSwitch("contributors", "info", arguments);
        var showAll = !showTitle && !showUrl && !showLabels && !showAssignees &&
                      !showReviewers && !showBranch && !showCommits && !showAuthor &&
                      !showStatus && !showIssues && !showContributors;

        var decorations = new ArrayList<String>();
        if (showAll || showTitle) {
            decorations.add("Title: ");
        }
        if (showAll || showUrl) {
            decorations.add("Url: ");
        }
        if (showAll || showAuthor) {
            decorations.add("Author: ");
        }
        if (showAll || showBranch) {
            decorations.add("Branch: ");
        }
        if (showAll || showLabels) {
            decorations.add("Labels: ");
        }
        if (showAll || showAssignees) {
            decorations.add("Assignees: ");
        }
        if (showAll || showReviewers) {
            decorations.add("Reviewers: ");
        }
        if (showAll || showStatus) {
            decorations.add("Status: ");
        }
        if (showAll || showChecks) {
            decorations.add("Checks: ");
        }
        if (showAll || showCommits) {
            decorations.add("Commits: ");
        }
        if (showAll || showIssues) {
            decorations.add("Issues: ");
        }
        if (showAll || showContributors) {
            decorations.add("Contributors: ");
        }

        var longest = decorations.stream()
                                 .mapToInt(String::length)
                                 .max()
                                 .orElse(0);
        var fmt = "%-" + longest + "s";

        if (showAll || showUrl) {
            if (showDecoration) {
                System.out.format(fmt, "URL:");
            }
            System.out.println(pr.webUrl());
        }

        if (showAll || showTitle) {
            if (showDecoration) {
                System.out.format(fmt, "Title:");
            }
            System.out.println(pr.title());
        }

        if (showAll || showAuthor) {
            if (showDecoration) {
                System.out.format(fmt, "Author:");
            }
            System.out.println(pr.author().userName());
        }

        if (showAll || showBranch) {
            if (showDecoration) {
                System.out.format(fmt, "Branch:");
            }
            System.out.println(pr.targetRef());
        }

        if (showAll || showLabels) {
            if (showDecoration) {
                System.out.format(fmt, "Labels:");
            }
            System.out.println(String.join(", ", pr.labels()));
        }

        if (showAll || showAssignees) {
            if (showDecoration) {
                System.out.format(fmt, "Assignees:");
            }
            var usernames = pr.assignees().stream().map(u -> u.userName()).collect(Collectors.toList());
            if (usernames.isEmpty()) {
                System.out.println("-");
            } else {
                System.out.println(String.join(", ", usernames));
            }
        }

        if (showAll || showReviewers) {
            if (showDecoration) {
                System.out.format(fmt, "Reviewers:");
            }
            var usernames = pr.reviews().stream().map(u -> u.reviewer().userName()).collect(Collectors.toList());
            if (usernames.isEmpty()) {
                System.out.println("-");
            } else {
                System.out.println(String.join(", ", usernames));
            }
        }

        if (showAll || showContributors) {
            if (showDecoration) {
                System.out.format(fmt, "Contributors:");
            }
            if (body.contributors().isEmpty()) {
                System.out.println("-");
            } else {
                System.out.println(String.join(", ", body.contributors()));
            }
        }

        if (showAll || showStatus) {
            if (showDecoration) {
                System.out.format(fmt, "Status:");
            }
            System.out.println(statusForPullRequest(pr));
        }

        if (showAll || showIssues) {
            var issues = body.issues()
                             .stream()
                             .map(URI::getPath)
                             .map(Path::of)
                             .map(Path::getFileName)
                             .map(Path::toString)
                             .collect(Collectors.toList());
            if (issues.size() == 0 || issues.size() == 1) {
                if (showDecoration) {
                    System.out.format(fmt, "Issue:");
                }
                if (issues.size() == 0) {
                    System.out.println("-");
                } else {
                    System.out.println(issues.get(0));
                }
            } else {
                System.out.println("Issues:");
                for (var issue : issues) {
                    System.out.println("- " + issue);
                }
            }
        }

        if (showAll || showChecks) {
            var checks = pr.checks(pr.headHash());
            var jcheck = Optional.ofNullable(checks.get("jcheck"));
            var submit = Optional.ofNullable(checks.get("submit"));
            if (jcheck.isPresent() || submit.isPresent()) {
                System.out.println("Checks:");
                if (jcheck.isPresent()) {
                    System.out.println("- jcheck: " + statusForCheck(jcheck.get()));
                }
                if (submit.isPresent()) {
                    System.out.println("- submit: " + statusForCheck(submit.get()));
                }
            }
        }

        if (showAll || showCommits) {
            var url = pr.repository().webUrl();
            var target = repo.fetch(url, pr.targetRef());
            var head = repo.fetch(url, pr.fetchRef());
            var mergeBase = repo.mergeBase(head, target);
            var commits = repo.commitMetadata(mergeBase, head);
            if (showDecoration) {
                System.out.println("Commits:");
            }
            for (var commit : commits) {
                System.out.println("- " + commit.hash().abbreviate() + ": " + commit.message().get(0));
            }
        }
    }
}
