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
package org.openjdk.skara.vcs.openjdk.convert;

import org.openjdk.skara.vcs.*;
import org.openjdk.skara.vcs.openjdk.*;
import java.io.IOException;
import java.nio.file.Files;
import java.net.URI;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;
import java.util.logging.Logger;

public class GitToHgConverter implements Converter {
    private final Logger log = Logger.getLogger("org.openjdk.skara.vcs.openjdk.convert");

    private String convertMessage(CommitMessage message, Author author, Author committer) {
        var sb = new StringBuilder();
        sb.append(message.title());
        sb.append("\n");

        var summaries = message.summaries();
        if (!summaries.isEmpty()) {
            sb.append("Summary: ");
            sb.append(String.join(" ", summaries));
            sb.append("\n");
        }

        var reviewers = message.reviewers();
        if (!reviewers.isEmpty()) {
            sb.append("Reviewed-by: ");
            sb.append(String.join(", ", reviewers));
            sb.append("\n");
        }

        var contributors = new ArrayList<String>();
        if (!author.equals(committer)) {
            contributors.add(author.toString());
        }
        contributors.addAll(message.contributors().stream().map(Author::toString).collect(Collectors.toList()));
        if (!contributors.isEmpty()) {
            sb.append("Contributed-by: ");
            sb.append(String.join(", ", contributors));
            sb.append("\n");
        }

        return sb.toString();
    }

    private String username(Author committer) {
        return committer.email().split("@")[0];
    }

    private Diff updateTags(Diff diff, ReadOnlyRepository gitRepo, Map<Hash, Hash> gitToHg) throws IOException {
        var patches = new ArrayList<Patch>();
        for (var patch : diff.patches()) {
            if (patch.target().path().isPresent()) {
                var targetPath = patch.target().path().get();
                if (targetPath.toString().equals(".hgtags") && patch.isTextual()) {
                    var hunks = new ArrayList<Hunk>();
                    for (var hunk : patch.asTextualPatch().hunks()) {
                        var targetLines = new ArrayList<String>();
                        for (var i = 0; i < hunk.target().lines().size(); i++) {
                            var line = hunk.target().lines().get(i);
                            var parts = line.split(" ");
                            var hash = parts[0];
                            if (hash.equals("0".repeat(40))) {
                                targetLines.add(line);
                            } else {
                                var tag = parts[1];
                                var gitHash = gitRepo.resolve(tag);
                                if (gitHash.isPresent()) {
                                    var newHgHash = gitToHg.get(gitHash.get());
                                    if (newHgHash == null) {
                                        throw new IllegalStateException("Have not converted git hash " + gitHash.get().hex() + " for tag " + tag);
                                    }
                                    log.finer("updating tag: " + tag + " -> " + newHgHash);
                                    targetLines.add(newHgHash.hex() + " " + tag);
                                } else {
                                    // can be an old tag that has been removed, just add it, will be removed later
                                    targetLines.add(line);
                                }
                            }
                        }
                        hunks.add(new Hunk(hunk.source().range(), hunk.source().lines(), hunk.source().hasNewlineAtEndOfFile(),
                                           hunk.target().range(), targetLines, hunk.target().hasNewlineAtEndOfFile()));
                    }
                    patches.add(new TextualPatch(patch.source().path().orElse(null), patch.source().type().orElse(null), patch.source().hash(),
                                                 patch.target().path().get(), patch.target().type().get(), patch.target().hash(),
                                                 patch.status(), hunks));
                } else {
                    patches.add(patch);
                }
            } else {
                patches.add(patch);
            }
        }
        return new Diff(diff.from(), diff.to(), patches);
    }

    private List<Hash> convert(Commits commits,
                               Repository hgRepo,
                               ReadOnlyRepository gitRepo,
                               Map<Hash, Hash> gitToHg,
                               Map<Hash, Hash> hgToGit) throws IOException {
        var hgHashes = new ArrayList<Hash>();
        for (var commit : commits) {
            log.fine("Converting: " + commit.hash().hex());
            var parents = commit.parents();
            if (commit.isInitialCommit()) {
                hgRepo.apply(commit.parentDiffs().get(0), true);
            } else if (parents.size() == 1) {
                var hgParent = gitToHg.get(parents.get(0));
                hgRepo.checkout(hgParent, false);
                hgRepo.apply(updateTags(commit.parentDiffs().get(0), gitRepo, gitToHg), true);
            } else if (parents.size() == 2) {
                var hgParent0 = gitToHg.get(parents.get(0));
                hgRepo.checkout(hgParent0, false);
                var hgParent1 = gitToHg.get(parents.get(1));
                hgRepo.merge(hgParent1, ":local");

                var parent0Diff = commit.parentDiffs().get(0);
                if (!parent0Diff.patches().isEmpty()) {
                    for (var patch : parent0Diff.patches()) {
                        if (patch.status().isAdded()) {
                            // There can be a merge conflict if the merge brings in a new file
                            // that also contains updates in the merge commit itself.
                            // Delete the file and re-create it using apply.
                            var target = hgRepo.root().resolve(patch.target().path().get());
                            if (Files.exists(target)) {
                                Files.delete(target);
                            }
                        }
                    }
                    hgRepo.apply(updateTags(parent0Diff, gitRepo, gitToHg), true);
                }
            } else {
                throw new IllegalStateException("More than two parents is not supported");
            }

            var author = commit.author();
            var committer = commit.committer();
            if (committer.email() == null) {
                throw new IllegalStateException("Commit " + commit.hash().hex() + " contains committer without email");
            }
            var message = CommitMessageParsers.v1.parse(commit.message());
            var hgMessage = convertMessage(message, author, committer);
            log.finest("Message: " + hgMessage);
            var hgAuthor = username(committer);
            log.finer("Author: " + hgAuthor);
            var hgHash = hgRepo.commit(hgMessage,
                                       username(committer),
                                       null,
                                       commit.date());
            log.finer("Hg hash: " + hgHash.hex());
            gitToHg.put(commit.hash(), hgHash);
            hgToGit.put(hgHash, commit.hash());
            hgHashes.add(hgHash);
        }

        return hgHashes;
    }

    private List<Mark> createMarks(List<Hash> hgHashes, Map<Hash, Hash> gitToHg, Map<Hash, Hash> hgToGit) {
        return createMarks(List.of(), hgHashes, gitToHg, hgToGit);
    }

    private List<Mark> createMarks(List<Mark> old, List<Hash> hgHashes, Map<Hash, Hash> gitToHg, Map<Hash, Hash> hgToGit) {
        var marks = new ArrayList<Mark>(old);
        for (var i = 0; i < hgHashes.size(); i++) {
            var hgHash = hgHashes.get(i);
            var gitHash = hgToGit.get(hgHash);
            if (gitHash == null) {
                throw new IllegalStateException("No git hash for hg hash " + hgHash.hex());
            }
            var key = old.size() + i + 1;
            marks.add(new Mark(key, hgHash, hgToGit.get(hgHash)));
        }
        return marks;
    }

    public List<Mark> convert(ReadOnlyRepository gitRepo, Repository hgRepo) throws IOException {
        var gitToHg = new HashMap<Hash, Hash>();
        var hgToGit = new HashMap<Hash, Hash>();
        try (var commits = gitRepo.commits(true)) {
            var hgHashes = convert(commits, hgRepo, gitRepo, gitToHg, hgToGit);
            return createMarks(hgHashes, gitToHg, hgToGit);
        }
    }

    public List<Mark> pull(Repository gitRepo, URI source, Repository hgRepo, List<Mark> oldMarks) throws IOException {
        var gitToHg = new HashMap<Hash, Hash>();
        var hgToGit = new HashMap<Hash, Hash>();
        for (var mark : oldMarks) {
            gitToHg.put(mark.git(), mark.hg());
            hgToGit.put(mark.hg(), mark.git());
        }

        var head = gitRepo.head();
        var fetchHead = gitRepo.fetch(source, "master:refs/remotes/origin/master");
        try (var commits = gitRepo.commits(head.toString() + ".." + fetchHead.toString(), true)) {
            var hgHashes = convert(commits, hgRepo, gitRepo, gitToHg, hgToGit);
            return createMarks(oldMarks, hgHashes, gitToHg, hgToGit);
        }
    }
}
