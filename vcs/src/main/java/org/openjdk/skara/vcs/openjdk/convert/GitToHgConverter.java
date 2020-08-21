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
    private final static Logger log = Logger.getLogger("org.openjdk.skara.vcs.openjdk.convert");
    private final Branch branch;
    private final List<Mark> marks = new ArrayList<Mark>();

    public GitToHgConverter() {
        this(new Branch("master"));
    }

    public GitToHgConverter(Branch branch) {
        this.branch = branch;
    }

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
            if (hash.equals(Hash.zero().hex())) {
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

    private boolean changesHgTags(List<StatusEntry> status) {
        var hgtags = Optional.of(Path.of(".hgtags"));
        return status.stream()
                     .filter(e -> e.status().isModified() || e.status().isAdded())
                     .anyMatch(e -> e.target().path().equals(hgtags));
    }

    private Hash hgHashFor(Map<Hash, Hash> gitToHg, Hash gitHash) {
        var hgHash = gitToHg.get(gitHash);
        if (hgHash == null) {
            throw new IllegalArgumentException("No known hg hash for git hash: " + gitHash.hex());
        }
        return hgHash;
    }

    private void convertTags(Repository hgRepo, ReadOnlyRepository gitRepo, Map<Hash, Hash> gitToHg) throws IOException {
        var gitTags = new TreeSet<String>();
        for (var tag : gitRepo.tags()) {
            gitTags.add(tag.name());
        }
        var hgTags = new TreeSet<String>();
        for (var tag : hgRepo.tags()) {
            hgTags.add(tag.name());
        }
        var missing = new TreeSet<String>(gitTags);
        missing.removeAll(hgTags);
        for (var name : missing) {
            var gitHash = gitRepo.resolve(name).orElseThrow(() ->
                    new IOException("Cannot resolve known tag " + name)
            );
            var hgHash = gitToHg.get(gitHash);
            var annotated = gitRepo.annotate(new Tag(name));
            if (annotated.isPresent()) {
                var msg = annotated.get().message();
                var user = username(annotated.get().author());
                var date = annotated.get().date();
                hgRepo.tag(hgHash, name, msg, user, null, date);
            } else {
                hgRepo.tag(hgHash, name, "Added tag " + name + " for " + hgHash.abbreviate(), "duke", null, null); 
            }
            var hgTagCommitHash = hgRepo.head();
            var last = marks.get(marks.size() - 1);
            var newMark = new Mark(last.key(), last.hg(), last.git(), hgTagCommitHash);
            marks.set(marks.size() - 1, newMark);
        }
    }

    private void convert(List<CommitMetadata> commits,
                         Repository hgRepo,
                         ReadOnlyRepository gitRepo,
                         Map<Hash, Hash> gitToHg) throws IOException {
        var hgRoot = hgRepo.root();
        var gitRoot = gitRepo.root();

        for (var commit : commits) {
            log.fine("Converting Git hash: " + commit.hash().hex());
            var parents = commit.parents();
            var gitParent0 = parents.get(0);
            var status0 = gitRepo.status(gitParent0, commit.hash());

            if (commit.isInitialCommit()) {
                apply(gitRepo, gitRoot, hgRepo, hgRoot, status0, commit.hash());
            } else if (parents.size() == 1) {
                var hgParent0 = hgHashFor(gitToHg, gitParent0);
                log.fine("Parent 0:" + hgParent0.hex());
                hgRepo.checkout(hgParent0, false);
                apply(gitRepo, gitRoot, hgRepo, hgRoot, status0, commit.hash());
            } else if (parents.size() == 2) {
                var hgParent0 = hgHashFor(gitToHg, gitParent0);
                log.fine("Parent 0:" + hgParent0.hex());
                hgRepo.checkout(hgParent0, false);

                var hgParent1 = hgHashFor(gitToHg, parents.get(1));
                log.fine("Parent 1:" + hgParent1.hex());
                hgRepo.merge(hgParent1, ":local", Repository.FastForward.DISABLE);
                hgRepo.revert(hgParent0);
                hgRepo.deleteUntrackedFiles();

                apply(gitRepo, gitRoot, hgRepo, hgRoot, status0, commit.hash());
            } else {
                throw new IllegalStateException("Merging more than two parents is not supported in Mercurial");
            }

            if (changesHgTags(status0)) {
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
            var date = commit.authored();
            log.finer("Date: " + date);

            Hash hgHash = null;
            if (parents.size() == 1 && status0.isEmpty()) {
                var tmp = Files.createFile(hgRoot.resolve("THIS_IS_A_REALLY_UNIQUE_FILE_NAME_THAT_CANT_POSSIBLY_BE_USED"));
                hgRepo.add(tmp);
                hgRepo.commit(hgMessage, hgAuthor, null, date);
                hgRepo.remove(tmp);
                hgHash = hgRepo.amend(hgMessage, hgAuthor, null);
            } else {
                hgHash = hgRepo.commit(hgMessage, hgAuthor, null, date);
            }
            log.fine("Converted hg hash: " + hgHash.hex());

            marks.add(new Mark(marks.size() + 1, hgHash, commit.hash()));
            gitToHg.put(commit.hash(), hgHash);
        }

        convertTags(hgRepo, gitRepo, gitToHg);
    }

    public List<Mark> marks() {
        return marks;
    }

    public List<Mark> convert(ReadOnlyRepository gitRepo, Repository hgRepo) throws IOException {
        return convert(gitRepo, hgRepo, List.of());
    }

    public List<Mark> convert(ReadOnlyRepository gitRepo, Repository hgRepo, List<Mark> oldMarks) throws IOException {
        var gitToHg = new HashMap<Hash, Hash>();
        for (var mark : oldMarks) {
            if (mark.tag().isPresent()) {
                gitToHg.put(mark.git(), mark.tag().get());
            } else {
                gitToHg.put(mark.git(), mark.hg());
            }
            marks.add(mark);
        }
        var gitCommits = gitRepo.commitMetadata(branch.name(), true);
        var converted = oldMarks.stream()
                                .map(Mark::git)
                                .collect(Collectors.toSet());
        var notConverted = gitCommits.stream()
                                     .filter(c -> !converted.contains(c.hash()))
                                     .collect(Collectors.toList());
        convert(notConverted, hgRepo, gitRepo, gitToHg);
        return marks;
    }

    public List<Mark> pull(Repository gitRepo, URI source, Repository hgRepo, List<Mark> oldMarks) throws IOException {
        var gitToHg = new HashMap<Hash, Hash>();
        for (var mark : oldMarks) {
            if (mark.tag().isPresent()) {
                gitToHg.put(mark.git(), mark.tag().get());
            } else {
                gitToHg.put(mark.git(), mark.hg());
            }
            marks.add(mark);
        }

        gitRepo.checkout(branch);
        var oldHead = gitRepo.head();
        gitRepo.pull(source.toString(), branch.name());
        var newHead = gitRepo.head();
        var commits = gitRepo.commitMetadata(gitRepo.rangeInclusive(oldHead, newHead), true);
        convert(commits, hgRepo, gitRepo, gitToHg);
        return marks;
    }
}
