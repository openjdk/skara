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
package org.openjdk.skara.bots.mlbridge;

import org.openjdk.skara.host.PullRequest;
import org.openjdk.skara.vcs.*;

import java.io.*;
import java.nio.file.Path;
import java.util.stream.Collectors;

class PullRequestInstance {
    private final PullRequest pr;
    private final Repository localRepo;
    private final Hash targetHash;
    private final Hash headHash;
    private final Hash baseHash;

    PullRequestInstance(Path localRepoPath, PullRequest pr) {
        this.pr = pr;

        // Materialize the PR's target ref
        try {
            var repository = pr.repository();
            localRepo = Repository.materialize(localRepoPath, repository.getUrl(), pr.getTargetRef());
            targetHash = localRepo.fetch(repository.getUrl(), pr.getTargetRef());
            headHash = localRepo.fetch(repository.getUrl(), pr.getHeadHash().hex());
            baseHash = localRepo.mergeBase(targetHash, headHash);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    Repository localRepo() {
        return this.localRepo;
    }

    Hash baseHash() {
        return this.baseHash;
    }

    Hash headHash() {
        return this.headHash;
    }

    String diffUrl() {
        return pr.getWebUrl() + ".diff";
    }

    String fetchCommand() {
        var repoUrl = pr.repository().getWebUrl();
        return "git fetch " + repoUrl + " " + pr.getSourceRef() + ":pull/" + pr.getId();
    }

    @FunctionalInterface
    interface CommitFormatter {
        String format(Commit commit);
    }

    String formatCommitMessages(Hash first, Hash last, CommitFormatter formatter) {
        try {
            var commits = localRepo().commits(first.hex() + ".." + last.hex());
            return commits.stream()
                          .map(formatter::format)
                          .collect(Collectors.joining("\n"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    String id() {
        return pr.getId();
    }
}
