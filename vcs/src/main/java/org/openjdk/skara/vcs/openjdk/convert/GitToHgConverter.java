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
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
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

    private byte[] updateTags(ReadOnlyRepository gitRepo, Map<Hash, Hash> gitToHg, byte[] content) throws IOException {
        var lines = new String(content, StandardCharsets.UTF_8).split("\n");
        var result = new StringBuilder();
        for (var line : lines) {
            var parts = line.split(" ");
            var hash = parts[0];
            if (hash.equals("0".repeat(40))) {
                result.append(line);
            } else {
                var tag = parts[1];
                var gitHash = gitRepo.resolve(tag);
                if (gitHash.isPresent()) {
                    var newHgHash = gitToHg.get(gitHash.get());
                    if (newHgHash != null) {
                        log.finer("updating tag: " + tag + " -> " + newHgHash);
                        result.append(newHgHash.hex() + " " + tag);
                    } else {
                        log.warning("Did not hg hash for git hash " + gitHash.get() + " for tag " + tag);
                        result.append(line);
                    }
                } else {
                    // can be an old tag that has been removed, just add it, will be removed later
                    log.warning("Did not find tag " + tag + " in git repo");
                    result.append(line);
                }
            }
            result.append("\n");
        }

        return result.toString().getBytes(StandardCharsets.UTF_8);
    }

    private void applyPatches(ReadOnlyRepository gitRepo, Path gitRoot, Repository hgRepo, Path hgRoot, List<Patch> patches, Hash to) throws IOException {
        apply(gitRepo, gitRoot, hgRepo, hgRoot, patches.stream().map(StatusEntry::new).collect(Collectors.toList()), to);
    }

    private void apply(ReadOnlyRepository gitRepo, Path gitRoot, Repository hgRepo, Path hgRoot, List<StatusEntry> entries, Hash to) throws IOException {
        var toRemove = new ArrayList<Path>();
        var toAdd = new ArrayList<Path>();
        var toDump = new ArrayList<Path>();

        for (var entry : entries) {
            var status = entry.status();
            if (status.isDeleted()) {
                toRemove.add(entry.source().path().orElseThrow());
            } else if (status.isRenamed()) {
                hgRepo.move(entry.source().path().orElseThrow(), entry.target().path().orElseThrow());
                toDump.add(entry.target().path().orElseThrow());
            } else if (status.isCopied()) {
                hgRepo.copy(entry.source().path().orElseThrow(), entry.target().path().orElseThrow());
                toDump.add(entry.target().path().orElseThrow());
            } else if (status.isModified() || status.isAdded()) {
                var targetPath = entry.target().path().orElseThrow();
                toDump.add(targetPath);
                if (status.isAdded()) {
                    toAdd.add(targetPath);
                }
            } else {
                throw new IOException("Unexpected status: " + status.toString());
            }
        }

        if (toDump.size() > 0) {
            for (var file : gitRepo.files(to, toDump)) {
                var hgPath = hgRoot.resolve(file.path());
                gitRepo.dump(file, hgPath);
                if (hgPath.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                    Files.setPosixFilePermissions(hgPath, file.type().permissions().orElseThrow());
                }
            }
        }

        if (toAdd.size() > 0) {
            hgRepo.add(toAdd);
        }

        if (toRemove.size() > 0) {
            hgRepo.remove(toRemove);
        }
    }

    private boolean changesHgTags(Commit c) {
        for (var diff : c.parentDiffs()) {
            for (var patch : diff.patches()) {
                var status = patch.status();
                if ((status.isModified() || status.isAdded()) &&
                    patch.target().path().orElseThrow().toString().equals(".hgtags")) {
                    return true;
                }
            }
        }

        return false;
    }

    private List<Hash> convert(Commits commits,
                               Repository hgRepo,
                               ReadOnlyRepository gitRepo,
                               Map<Hash, Hash> gitToHg,
                               Map<Hash, Hash> hgToGit) throws IOException {
        var hgRoot = hgRepo.root();
        var gitRoot = gitRepo.root();
        var hgHashes = new ArrayList<Hash>();
        for (var commit : commits) {
            log.fine("Converting: " + commit.hash().hex());
            var parents = commit.parents();
            var hgParent0 = gitToHg.get(parents.get(0));
            var patches0 = commit.parentDiffs().get(0).patches();

            if (commit.isInitialCommit()) {
                applyPatches(gitRepo, gitRoot, hgRepo, hgRoot, patches0, commit.hash());
            } else if (parents.size() == 1) {
                hgRepo.checkout(hgParent0, false);
                applyPatches(gitRepo, gitRoot, hgRepo, hgRoot, patches0, commit.hash());
            } else if (parents.size() == 2) {
                hgRepo.checkout(hgParent0, false);

                var hgParent1 = gitToHg.get(parents.get(1));
                hgRepo.merge(hgParent1, ":local");
                hgRepo.revert(hgParent0);

                var changes = gitRepo.status(parents.get(0), commit.hash());
                apply(gitRepo, gitRoot, hgRepo, hgRoot, changes, commit.hash());
            } else {
                throw new IllegalStateException("More than two parents is not supported");
            }

            if (changesHgTags(commit)) {
                var content = gitRepo.show(Path.of(".hgtags"), commit.hash()).orElseThrow();
                var adjustedContent = updateTags(gitRepo, gitToHg, content);
                Files.write(hgRoot.resolve(".hgtags"), adjustedContent, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
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

            Hash hgHash = null;
            if (parents.size() == 1 && patches0.isEmpty()) {
                var tmp = Files.createFile(hgRoot.resolve("THIS_IS_A_REALLY_UNIQUE_FILE_NAME_THAT_CANT_POSSIBLY_BE_USED"));
                hgRepo.add(tmp);
                hgRepo.commit(hgMessage, hgAuthor, null, commit.date());
                hgRepo.remove(tmp);
                hgHash = hgRepo.amend(hgMessage, hgAuthor, null);
            } else {
                hgHash = hgRepo.commit(hgMessage,
                                       hgAuthor,
                                       null,
                                       commit.date());
            }
            log.fine("Converted hg hash: " + hgHash.hex());
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
