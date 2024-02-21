/*
 * Copyright (c) 2024, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.bots.notify.notes;

import org.openjdk.skara.bots.notify.*;
import org.openjdk.skara.forge.*;
import org.openjdk.skara.issuetracker.*;
import org.openjdk.skara.vcs.*;
import org.openjdk.skara.vcs.openjdk.CommitMessageParsers;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

class CommitNoteNotifier implements Notifier, PullRequestListener {
    private final Logger log = Logger.getLogger("org.openjdk.skara.bots.notify");

    private final IssueProject issueProject;

    CommitNoteNotifier(IssueProject issueProject) {
        this.issueProject = issueProject;
    }

    private List<IssueTrackerIssue> issues(Commit commit) {
        var commitMessage = CommitMessageParsers.v1.parse(commit.metadata());
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
    public void onIntegratedPullRequest(PullRequest pr, Path scratchPath, Hash hash)  {
        try {
            var pool = new HostedRepositoryPool(scratchPath.resolve("pool"));
            var localRepoDir = scratchPath.resolve(pr.repository().name());
            var localRepo = pool.materialize(pr.repository(), localRepoDir);
            localRepo.fetch(pr.repository().authenticatedUrl(), hash.hex(), true);

            var commit = pr.repository().commit(hash).orElseThrow(() ->
                    new IllegalStateException("Integrated commit " + hash +
                                            " not present in repository " + pr.repository().webUrl())
            );
            var issues = issues(commit);

            var note = new ArrayList<String>();
            note.add("Commit: " + commit.webUrl());
            note.add("Review: " + pr.webUrl());
            if (!issues.isEmpty()) {
                note.add("Issues:");
                for (var issue : issues) {
                    note.add("- " + issue.webUrl());
                }
            }

            localRepo.fetch(pr.repository().authenticatedUrl(), "refs/notes/*:refs/notes/*");
            var existingNotes = localRepo.notes(hash);
            if (existingNotes.isEmpty()) {
                localRepo.addNote(hash, note, "Duke", "duke@openjdk.org");
                localRepo.pushNotes(pr.repository().authenticatedUrl());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public String name() {
        return "notes";
    }
}
