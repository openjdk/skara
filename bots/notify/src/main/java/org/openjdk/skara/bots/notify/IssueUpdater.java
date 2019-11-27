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

import org.openjdk.skara.forge.HostedRepository;
import org.openjdk.skara.issuetracker.Issue;
import org.openjdk.skara.issuetracker.IssueProject;
import org.openjdk.skara.vcs.*;
import org.openjdk.skara.vcs.openjdk.*;

import java.util.List;
import java.util.logging.Logger;

public class IssueUpdater implements UpdateConsumer {
    private final IssueProject issueProject;
    private final Logger log = Logger.getLogger("org.openjdk.skara.bots.notify");

    IssueUpdater(IssueProject issueProject) {
        this.issueProject = issueProject;
    }

    @Override
    public void handleCommits(HostedRepository repository, List<Commit> commits, Branch branch) {
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
                issue.get().setState(Issue.State.CLOSED);
            }
        }
    }

    @Override
    public void handleOpenJDKTagCommits(HostedRepository repository, List<Commit> commits, OpenJDKTag tag, Tag.Annotated annotated) {

    }

    @Override
    public void handleTagCommit(HostedRepository repository, Commit commit, Tag tag, Tag.Annotated annotation) {

    }

    @Override
    public void handleNewBranch(HostedRepository repository, List<Commit> commits, Branch parent, Branch branch) {

    }
}
