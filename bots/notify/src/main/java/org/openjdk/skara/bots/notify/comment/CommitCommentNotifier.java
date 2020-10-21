/*
 * Copyright (c) 2020, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.bots.notify.comment;

import org.openjdk.skara.bots.notify.*;
import org.openjdk.skara.forge.*;
import org.openjdk.skara.issuetracker.*;
import org.openjdk.skara.vcs.*;
import org.openjdk.skara.vcs.openjdk.CommitMessageParsers;

import java.util.*;
import java.util.stream.Collectors;

class CommitCommentNotifier implements Notifier, PullRequestListener {
    private final IssueProject issueProject;

    CommitCommentNotifier(IssueProject issueProject) {
        this.issueProject = issueProject;
    }

    private List<Issue> issues(CommitMetadata metadata) {
        var commitMessage = CommitMessageParsers.v1.parse(metadata);
        return commitMessage.issues()
                            .stream()
                            .map(i -> issueProject.issue(i.shortId()))
                            .filter(Optional::isPresent)
                            .map(Optional::get)
                            .collect(Collectors.toList());
    }

    @Override
    public void attachTo(Emitter e) {
        e.registerPullRequestListener(this);
    }

    @Override
    public void onIntegratedPullRequest(PullRequest pr, Hash hash)  {
        var repository = pr.repository();
        var commit = repository.commit(hash).orElseThrow(() ->
                new IllegalStateException("Integrated commit " + hash +
                                          " not present in repository " + repository.webUrl())
        );
        var comment = new ArrayList<String>();
        comment.addAll(List.of(
            "<!-- COMMIT COMMENT NOTIFICATION -->",
            "### Review",
            "",
            "- [" + pr.repository().name() + "/" + pr.id() + "](" + pr.webUrl() + ")"
        ));
        var issues = issues(commit.metadata());
        if (issues.size() > 0) {
            comment.add("");
            comment.add("### Issues");
            comment.add("");
            for (var issue : issues) {
                comment.add("- [" + issue.id() + "](" + issue.webUrl() + ")");
            }
        }
        repository.addCommitComment(hash, String.join("\n", comment));
    }
}
