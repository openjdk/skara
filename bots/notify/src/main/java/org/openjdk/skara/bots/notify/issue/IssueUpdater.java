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
package org.openjdk.skara.bots.notify.issue;

import org.openjdk.skara.bots.notify.*;
import org.openjdk.skara.email.EmailAddress;
import org.openjdk.skara.forge.*;
import org.openjdk.skara.issuetracker.*;
import org.openjdk.skara.vcs.*;
import org.openjdk.skara.vcs.openjdk.CommitMessageParsers;

import java.net.URI;
import java.util.*;
import java.util.logging.Logger;

public class IssueUpdater implements RepositoryUpdateConsumer, PullRequestUpdateConsumer {
    private final IssueProject issueProject;
    private final boolean reviewLink;
    private final URI reviewIcon;
    private final boolean commitLink;
    private final URI commitIcon;
    private final Logger log = Logger.getLogger("org.openjdk.skara.bots.notify");

    IssueUpdater(IssueProject issueProject, boolean reviewLink, URI reviewIcon, boolean commitLink, URI commitIcon) {
        this.issueProject = issueProject;
        this.reviewLink = reviewLink;
        this.reviewIcon = reviewIcon;
        this.commitLink = commitLink;
        this.commitIcon = commitIcon;
    }

    public static IssueUpdaterBuilder newBuilder() {
        return new IssueUpdaterBuilder();
    }

    @Override
    public String name() {
        return "issue";
    }

    private Optional<String> findIssueUsername(Commit commit) {
        var authorEmail = EmailAddress.from(commit.author().email());
        if (authorEmail.domain().equals("openjdk.org")) {
            return Optional.of(authorEmail.localPart());
        }

        var committerEmail = EmailAddress.from(commit.committer().email());
        if (!committerEmail.domain().equals("openjdk.org")) {
            log.severe("Cannot determine issue tracker user name from committer email: " + committerEmail);
            return Optional.empty();
        }
        return Optional.of(committerEmail.localPart());
    }

    @Override
    public void handleCommits(HostedRepository repository, Repository localRepository, List<Commit> commits, Branch branch)  {
        for (var commit : commits) {
            var commitMessage = CommitMessageParsers.v1.parse(commit);
            for (var commitIssue : commitMessage.issues()) {
                var optionalIssue = issueProject.issue(commitIssue.shortId());
                if (optionalIssue.isEmpty()) {
                    log.severe("Cannot update issue " + commitIssue.id() + " with commit " + commit.hash().abbreviate()
                            + " - issue not found in issue project");
                    continue;
                }
                var issue = optionalIssue.get();

                var candidates = repository.findPullRequestsWithComment(null, "Pushed as commit " + commit.hash() + ".");
                if (candidates.size() != 1) {
                    log.info("IssueUpdater@" + issue.id() + ": Skipping commit " + commit.hash().abbreviate() + " for repository " + repository.name() +
                            " on branch " + branch.name() + " - " + candidates.size() + " matching PRs found (needed 1)");
                    continue;
                }
                var candidate = candidates.get(0);
                var prLink = candidate.webUrl();
                if (!candidate.targetRef().equals(branch.name())) {
                    log.info("IssueUpdater@" + issue.id() + ": Pull request " + prLink + " targets " + candidate.targetRef() + " - commit is on " + branch.toString() + " - skipping");
                    continue;
                }

                if (commitLink) {
                    var linkBuilder = Link.create(repository.webUrl(commit.hash()), "Commit")
                                          .summary(repository.name() + "/" + commit.hash().abbreviate());
                    if (commitIcon != null) {
                        linkBuilder.iconTitle("Commit");
                        linkBuilder.iconUrl(commitIcon);
                    }
                    issue.addLink(linkBuilder.build());
                }

                if (issue.state() == Issue.State.OPEN) {
                    issue.setState(Issue.State.RESOLVED);
                    if (issue.assignees().isEmpty()) {
                        var username = findIssueUsername(commit);
                        if (username.isPresent()) {
                            var assignee = issueProject.issueTracker().user(username.get());
                            if (assignee.isPresent()) {
                                issue.setAssignees(List.of(assignee.get()));
                            }
                        }
                    }
                }
            }
        }
    }

    @Override
    public void handleNewIssue(PullRequest pr, org.openjdk.skara.vcs.openjdk.Issue issue) {
        var realIssue = issueProject.issue(issue.shortId());
        if (realIssue.isEmpty()) {
            log.warning("Pull request " + pr + " added unknown issue: " + issue.id());
            return;
        }

        if (reviewLink) {
            var linkBuilder = Link.create(pr.webUrl(), "Review")
                                  .summary(pr.repository().name() + "/" + pr.id());
            if (reviewIcon != null) {
                linkBuilder.iconTitle("Review");
                linkBuilder.iconUrl(reviewIcon);
            }

            realIssue.get().addLink(linkBuilder.build());
        }
    }

    @Override
    public void handleRemovedIssue(PullRequest pr, org.openjdk.skara.vcs.openjdk.Issue issue) {
        var realIssue = issueProject.issue(issue.shortId());
        if (realIssue.isEmpty()) {
            log.warning("Pull request " + pr + " removed unknown issue: " + issue.id());
            return;
        }

        var link = Link.create(pr.webUrl(), "").build();
        realIssue.get().removeLink(link);
    }
}
