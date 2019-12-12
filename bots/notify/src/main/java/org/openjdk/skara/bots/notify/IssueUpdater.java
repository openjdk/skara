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
package org.openjdk.skara.bots.notify;

import org.openjdk.skara.forge.*;
import org.openjdk.skara.issuetracker.Issue;
import org.openjdk.skara.issuetracker.*;
import org.openjdk.skara.jcheck.JCheckConfiguration;
import org.openjdk.skara.vcs.*;
import org.openjdk.skara.vcs.openjdk.*;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

public class IssueUpdater implements RepositoryUpdateConsumer, PullRequestUpdateConsumer {
    private final IssueProject issueProject;
    private final URI reviewIcon;
    private final URI commitIcon;
    private final Logger log = Logger.getLogger("org.openjdk.skara.bots.notify");

    IssueUpdater(IssueProject issueProject, URI reviewIcon, URI commitIcon) {
        this.issueProject = issueProject;
        this.reviewIcon = reviewIcon;
        this.commitIcon = commitIcon;
    }

    @Override
    public void handleCommits(HostedRepository repository, Repository localRepository, List<Commit> commits, Branch branch) {
        for (var commit : commits) {
            var commitNotification = CommitFormatters.toTextBrief(repository, commit);
            var commitMessage = CommitMessageParsers.v1.parse(commit);
            for (var commitIssue : commitMessage.issues()) {
                var issue = issueProject.issue(commitIssue.id());
                if (issue.isEmpty()) {
                    log.severe("Cannot update issue " + commitIssue.id() + " with commit " + commit.hash().abbreviate()
                                       + " - issue not found in issue project");
                    continue;
                }
                issue.get().addComment(commitNotification);
                issue.get().setState(Issue.State.RESOLVED);

                var linkBuilder = Link.create(repository.webUrl(commit.hash()), "Commit")
                                      .summary(repository.name() + "/" + commit.hash().abbreviate());
                if (commitIcon != null) {
                    linkBuilder.iconTitle("Commit");
                    linkBuilder.iconUrl(commitIcon);
                }
                issue.get().addLink(linkBuilder.build());

                try {
                    var conf = localRepository.lines(Path.of(".jcheck/conf"), commit.hash());
                    if (conf.isPresent()) {
                        var parsed = JCheckConfiguration.parse(conf.get());
                        var version = parsed.general().version();
                        version.ifPresent(v -> issue.get().addFixVersion(v));
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    @Override
    public void handleOpenJDKTagCommits(HostedRepository repository, Repository loclRepository, List<Commit> commits, OpenJDKTag tag, Tag.Annotated annotated) {

    }

    @Override
    public void handleTagCommit(HostedRepository repository, Repository loclRepository, Commit commit, Tag tag, Tag.Annotated annotation) {

    }

    @Override
    public void handleNewBranch(HostedRepository repository, Repository loclRepository, List<Commit> commits, Branch parent, Branch branch) {

    }

    @Override
    public void handleNewIssue(PullRequest pr, org.openjdk.skara.vcs.openjdk.Issue issue) {
        var realIssue = issueProject.issue(issue.id());
        if (realIssue.isEmpty()) {
            log.warning("Pull request " + pr + " added unknown issue: " + issue.id());
            return;
        }

        var linkBuilder = Link.create(pr.webUrl(), "Review")
                              .summary(pr.repository().name() + "/" + pr.id());
        if (reviewIcon != null) {
            linkBuilder.iconTitle("Review");
            linkBuilder.iconUrl(reviewIcon);
        }

        realIssue.get().addLink(linkBuilder.build());
    }

    @Override
    public void handleRemovedIssue(PullRequest pr, org.openjdk.skara.vcs.openjdk.Issue issue) {
        var realIssue = issueProject.issue(issue.id());
        if (realIssue.isEmpty()) {
            log.warning("Pull request " + pr + " removed unknown issue: " + issue.id());
            return;
        }

        realIssue.get().removeLink(pr.webUrl());
    }
}
