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
package org.openjdk.skara.vcs.openjdk.convert;

import org.openjdk.skara.vcs.*;
import org.openjdk.skara.vcs.openjdk.*;

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static java.lang.Integer.parseInt;

public class HgToGitConverter implements Converter {
    private static class ProcessInfo implements AutoCloseable {
        private final java.lang.Process process;
        private final List<String> command;
        private final Path stdout;
        private final Path stderr;
        private final CloseAction closeAction;

        @FunctionalInterface
        interface CloseAction {
            void close() throws IOException;
        }

        ProcessInfo(java.lang.Process process, List<String> command, Path stdout, Path stderr, CloseAction closeAction) {
            this.process = process;
            this.command = command;
            this.stdout = stdout;
            this.stderr = stderr;
            this.closeAction = closeAction;
        }

        ProcessInfo(java.lang.Process process, List<String> command, Path stdout, Path stderr) {
            this(process, command, stdout, stderr, () -> {});
        }

        java.lang.Process process() {
            return process;
        }

        List<String> command() {
            return command;
        }

        Path stdout() {
            return stdout;
        }

        Path stderr() {
            return stderr;
        }

        int waitForProcess() throws InterruptedException {
            var finished = process.waitFor(12, TimeUnit.HOURS);
            if (!finished) {
                process.destroyForcibly().waitFor();
                throw new RuntimeException("Command '" + String.join(" ", command) + "' did not finish in 12 hours");
            }
            return process.exitValue();
        }

        @Override
        public void close() throws IOException {
            if (stdout != null) {
                Files.delete(stdout);
            }
            if (stderr != null) {
                Files.delete(stderr);
            }
            closeAction.close();
        }
    }

    private final byte[] fileBuffer = new byte[2048];
    private final Logger log = Logger.getLogger("org.openjdk.skara.vcs.openjdk.convert");

    private final Map<Hash, List<String>> replacements;
    private final Map<Hash, Map<String, String>> corrections;
    private final Set<Hash> lowercase;
    private final Set<Hash> punctuated;

    private final Map<String, String> authorMap;
    private final Map<String, String> contributorMap;
    private final Map<String, List<String>> sponsorMap;

    private final CommitMessageParser parser = new ConverterCommitMessageParser();
    private int currentMark = 0;
    private final Map<Hash, Integer> hgHashesToMarks = new HashMap<Hash, Integer>();
    private final Map<Integer, Hash> marksToHgHashes = new HashMap<Integer, Hash>();

    public HgToGitConverter(Map<Hash, List<String>> replacements,
                            Map<Hash, Map<String, String>> corrections,
                            Set<Hash> lowercase,
                            Set<Hash> punctuated,
                            Map<String, String> authorMap,
                            Map<String, String> contributorMap,
                            Map<String, List<String>> sponsorMap) {
        this.replacements = replacements;
        this.corrections = corrections;
        this.lowercase = lowercase;
        this.punctuated = punctuated;

        this.authorMap = authorMap;
        this.contributorMap = contributorMap;
        this.sponsorMap = sponsorMap;
    }

    private static Branch convertBranch(Branch branch) {
        if (branch.name().equals("default")) {
            return new Branch("master");
        }

        return branch;
    }

    private static String convertFlags(String flags) {
        if (flags.contains("x")) {
            return "100755";
        }

        if (flags.contains("l")) {
            return "120000";
        }

        return "100644";
    }

    private static String capitalize(String s) {
        return s.substring(0, 1).toUpperCase() + s.substring(1);
    }

    private static String removePunctuation(String s) {
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }

    private int nextMark(Hash hgHash) {
        currentMark++;
        var next = currentMark;
        hgHashesToMarks.put(hgHash, next);
        marksToHgHashes.put(next, hgHash);
        return next;
    }

    private Author convertAuthor(Author from) {
        var author = authorMap.get(from.name());
        if (author == null) {
            throw new RuntimeException("Failed to find author mapping for: " + from.name());
        }
        return Author.fromString(author);
    }

    private Attribution attribute(List<Author> contributorsFromCommit, Author hgAuthor) {
        var isSponsored = false;
        var contributors = new ArrayList<Author>(contributorsFromCommit);
        if (contributors.size() == 1) {
            isSponsored = true;
        } else if (contributors.size() > 1) {
            // The author has sponsored at least one commit, see if this commit was sponsored.
            // The commit is sponsored if the author is *not* listed on the "Contributed-by" line.

            var emails = sponsorMap.get(hgAuthor.name());
            if (emails == null) {
                throw new RuntimeException("Failed to find sponsor mapping for: " + hgAuthor.name());
            }
            Author authorAsContributor = null;
            for (var email : emails) {
                for (var contributor : contributors) {
                    if (contributor.email().equals(email)) {
                        authorAsContributor = contributor;
                        break;
                    }
                }
            }
            if (authorAsContributor != null) {
                contributors.remove(authorAsContributor);
            } else {
                isSponsored = true;
            }
        }

        var originalAuthor = convertAuthor(hgAuthor);

        Author author = null;
        if (isSponsored) {
            author = new Author(contributors.get(0).name(), contributors.get(0).email());
            contributors.remove(0);
        } else {
            author = originalAuthor;
        }
        var committer = isSponsored ? originalAuthor : author;

        return new Attribution(author, committer, contributors);
    }

    private List<Author> addContributorNames(List<Author> contributors) {
        final Function<Author, String> lookup = (Author a) -> {
            var author = contributorMap.get(a.email());
            if (author == null) {
                throw new RuntimeException("Failed to find contributor mapping for: " + a.email());
            }
            return author;
        };
        return contributors.stream()
                           .map(a -> a.name().isEmpty() ? Author.fromString(lookup.apply(a)) : a)
                           .collect(Collectors.toList());
    }

    private static List<String> cleanup(List<String> original, Map<String, String> corrections) {
        if (corrections == null) {
            return original;
        }

        return original.stream().map(l -> corrections.getOrDefault(l, l)).collect(Collectors.toList());
    }

    private String toGitCommitMessage(Hash hash, List<Issue> issues, List<String> summaries, List<Author> contributors, List<String> reviewers, List<String> others) {
        List<String> body = new ArrayList<String>();
        body.addAll(summaries.stream().map(HgToGitConverter::capitalize).collect(Collectors.toList()));
        body.addAll(others);

        var subject = issues.stream().map(Issue::toString).collect(Collectors.toList());
        if (subject.size() == 0) {
            subject = body.subList(0, 1);
            body = body.subList(1, body.size());
        }

        var firstNonNewlineIndex = 0;
        while (firstNonNewlineIndex < body.size() && body.get(firstNonNewlineIndex).equals("")) {
            firstNonNewlineIndex++;
        }
        body = body.subList(firstNonNewlineIndex, body.size());

        var sb = new StringBuilder();
        for (var line : subject) {
            line = lowercase.contains(hash) ? line : capitalize(line);
            line = punctuated.contains(hash) ? line : removePunctuation(line);
            if (line.startsWith("JMC-")) {
                line = line.substring(4);
            }
            sb.append(line);
            sb.append("\n");
        }
        if ((body.size() + contributors.size() + reviewers.size()) > 0) {
            sb.append("\n");
        }

        var hasPrintedNonNewline = false;
        for (var line : body) {
            // Remove any number of initial empty lines
            if (!hasPrintedNonNewline && line.equals("")) {
                continue;
            }
            sb.append(line);
            sb.append("\n");
            hasPrintedNonNewline = true;
        }
        if (body.size() > 0) {
            sb.append("\n");
        }

        for (var contributor : contributors) {
            sb.append("Co-authored-by: ");
            sb.append(contributor.toString());
            sb.append("\n");
        }

        if (reviewers.size() > 0) {
            sb.append("Reviewed-by: ");
            sb.append(String.join(", ", reviewers));
            sb.append("\n");
        }

        return sb.toString();
    }

    private GitCommitMetadata convertMetadata(Hash hgHash,
                                              Branch hgBranch,
                                              Author hgAuthor,
                                              List<Hash> hgParentHashes,
                                              ZonedDateTime hgDate,
                                              List<String> hgCommitMessage) {
        var shortHash = new Hash(hgHash.hex().substring(0, 12));

        hgCommitMessage = replacements.getOrDefault(shortHash, hgCommitMessage);
        hgCommitMessage = cleanup(hgCommitMessage, corrections.get(shortHash));

        var commitMessage = parser.parse(hgCommitMessage);
        var hgContributors = addContributorNames(commitMessage.contributors());

        var attribution = attribute(hgContributors, hgAuthor);
        var gitAuthor = attribution.author();
        var gitCommitter = attribution.committer();
        var gitMessage = toGitCommitMessage(shortHash,
                                            commitMessage.issues(),
                                            commitMessage.summaries(),
                                            attribution.contributors(),
                                            commitMessage.reviewers(),
                                            commitMessage.additional());

        var gitMark = nextMark(hgHash);
        var gitParentMarks = hgParentHashes.stream().map(hgHashesToMarks::get).collect(Collectors.toList());

        var gitBranch = convertBranch(hgBranch);
        var gitDate = hgDate; // no conversion needed

        return new GitCommitMetadata(gitMark, gitParentMarks, gitAuthor, gitCommitter, gitBranch, gitDate, gitMessage);
    }

    private List<Hash> convertCommits(Pipe pipe) throws IOException {
        var tagCommits = new ArrayList<Hash>();
        while (pipe.read() != -1) {
            pipe.readln(); // skip delimiter
            var hash = new Hash(pipe.readln());
            log.fine("Converting: " + hash.hex());
            pipe.readln(); // skip revision number
            var branch = new Branch(pipe.readln());
            log.finer("Branch: " + branch.name());

            var parents = pipe.readln();
            log.finer("Parents: " + parents);
            var parentHashes = Arrays.asList(parents.split(" "))
                                     .stream()
                                     .map(Hash::new)
                                     .collect(Collectors.toList());
            if (parentHashes.size() == 1 && parentHashes.get(0).equals(Hash.zero())) {
                parentHashes = new ArrayList<Hash>();
            }
            pipe.readln(); // skip parent revisions

            var author = Author.fromString(pipe.readln());
            log.finer("Author: " + author.toString());

            var formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd H:m:sZ");
            var date = ZonedDateTime.parse(pipe.readln(), formatter);
            log.finer("Date: " + date.toString());

            var messageSize = parseInt(pipe.readln());
            var messageBuffer = pipe.read(messageSize);
            var hgMessage = new String(messageBuffer, 0, messageSize, StandardCharsets.UTF_8);
            log.finest("Message: " + hgMessage);

            var metadata = convertMetadata(hash,
                                           branch,
                                           author,
                                           parentHashes,
                                           date,
                                           Arrays.asList(hgMessage.split("\n")));

            pipe.print("commit refs/heads/");
            pipe.println(metadata.branch().name());

            pipe.print("mark :");
            pipe.println(metadata.mark());

            var epoch = metadata.date().toEpochSecond();
            var offset = metadata.date().format(DateTimeFormatter.ofPattern("xx"));

            pipe.print("author ");
            pipe.print(metadata.author().name());
            pipe.print(" <");
            pipe.print(metadata.author().email());
            pipe.print("> ");
            pipe.print(epoch);
            pipe.print(" ");
            pipe.println(offset);

            pipe.print("committer ");
            pipe.print(metadata.committer().name());
            pipe.print(" <");
            pipe.print(metadata.committer().email());
            pipe.print("> ");
            pipe.print(epoch);
            pipe.print(" ");
            pipe.println(offset);

            pipe.print("data ");

            var gitMessage = metadata.message().getBytes(StandardCharsets.UTF_8);
            pipe.println(gitMessage.length);
            pipe.print(gitMessage);

            if (metadata.parents().size() > 0) {
                pipe.print("from :");
                pipe.println(metadata.parents().get(0));
            }
            if (metadata.parents().size() > 1) {
                pipe.print("merge :");
                pipe.println(metadata.parents().get(1));
            }

            // Stream the file content
            var numModified = parseInt(pipe.readln());
            var numAdded = parseInt(pipe.readln());
            var numRemoved = parseInt(pipe.readln());

            for (var i = 0; i < (numAdded + numModified); i++) {
                var filename = pipe.readln();
                var flags = pipe.readln();

                if (filename.equals(".hgtags") && parentHashes.size() == 1) {
                    tagCommits.add(hash);
                }

                log.finest("M " + filename);
                pipe.print("M ");
                pipe.print(convertFlags(flags));
                pipe.print(" inline ");
                pipe.println(filename);

                var numBytes = parseInt(pipe.readln());
                pipe.print("data ");
                pipe.println(numBytes);

                var leftToRead = numBytes;
                while (leftToRead != 0) {
                    var numRead = pipe.read(fileBuffer, 0, Math.min(fileBuffer.length, leftToRead));
                    if (numRead == -1) {
                        throw new IOException("Unexpected end of input");
                    }
                    pipe.print(fileBuffer, 0, numRead);
                    leftToRead -= numRead;
                }
            }

            for (var i = 0; i < numRemoved; i++) {
                var filename = pipe.readln();
                log.finest("D " + filename);
                pipe.print("D ");
                pipe.println(filename);
            }
        }


        return tagCommits;
    }

    private void convertTags(Pipe pipe, List<Hash> tagCommits, ReadOnlyRepository hgRepo) throws IOException {
        var tags = new HashMap<String, Commit>();
        for (var tagHash : tagCommits) {
            log.fine("Inspecting tag commit " + tagHash.toString());
            var commit = hgRepo.lookup(tagHash).orElseThrow(() -> new IOException("Could not find commit " + tagHash));
            var diff = commit.parentDiffs().get(0); // convert never returns merge commits
            for (var patch : diff.patches()) {
                var target = patch.target().path();
                if (target.isPresent() && target.get().equals(Path.of(".hgtags"))) {
                    for (var hunk : patch.asTextualPatch().hunks()) {
                        for (var line : hunk.target().lines()) {
                            if (line.isEmpty()) {
                                continue;
                            }
                            var parts = line.split(" ");
                            var hash = parts[0];
                            var tag = parts[1];
                            if (hash.equals(Hash.zero().hex())) {
                                tags.remove(tag);
                            } else {
                                tags.put(tag, commit);
                            }
                        }
                    }
                }
            }
        }

        for (var tag : hgRepo.tags()) {
            if (tags.containsKey(tag.name())) {
                var commit = tags.get(tag.name());

                log.fine("Converting tag " + tag.name());
                pipe.print("tag ");
                pipe.println(tag.name());
                pipe.print("from :");
                pipe.println(hgHashesToMarks.get(hgRepo.lookup(tag).get().hash()));

                pipe.print("tagger ");
                var author = convertAuthor(commit.author());
                pipe.print(author.name());
                pipe.print(" <");
                pipe.print(author.email());
                pipe.print("> ");
                var epoch = commit.authored().toEpochSecond();
                var offset = commit.authored().format(DateTimeFormatter.ofPattern("xx"));
                pipe.print(epoch);
                pipe.print(" ");
                pipe.println(offset);

                pipe.print("data ");
                var message = String.join("\n", commit.message());
                var bytes = message.getBytes(StandardCharsets.UTF_8);
                pipe.println(bytes.length);
                pipe.print(bytes);
            }
        }
    }

    private List<Mark> readMarks(Path p) throws IOException {
        var marks = new ArrayList<Mark>();
        try (var reader = Files.newBufferedReader(p)) {
            for (var line = reader.readLine(); line != null; line = reader.readLine()) {
                var parts = line.split(" ");
                var mark = parseInt(parts[0].substring(1));
                var gitHash = new Hash(parts[1]);
                var hgHash = marksToHgHashes.get(mark);
                log.finest("parsed mark " + mark + ", hg: " + hgHash.hex() + ", git: " + gitHash.hex());
                marks.add(new Mark(mark, hgHash, gitHash));
            }
        }
        return marks;
    }

    private Path writeMarks(List<Mark> marks) throws IOException {
        var gitMarks = Files.createTempFile("git", ".marks.txt");
        try (var writer = Files.newBufferedWriter(gitMarks)) {
            for (var mark : marks) {
                writer.write(":");
                writer.write(Integer.toString(mark.key()));
                writer.write(" ");
                writer.write(mark.git().hex());
                writer.newLine();

                marksToHgHashes.put(mark.key(), mark.hg());
                hgHashesToMarks.put(mark.hg(), mark.key());
            }
        }
        return gitMarks;
    }

    private ProcessInfo dump(ReadOnlyRepository repo) throws IOException {
        var ext = Files.createTempFile("ext", ".py");
        Files.copy(this.getClass().getResourceAsStream("/ext.py"), ext, StandardCopyOption.REPLACE_EXISTING);

        var command = List.of("hg", "--config", "extensions.dump=" + ext.toAbsolutePath().toString(), "dump");
        var pb = new ProcessBuilder(command);
        pb.environment().put("HGRCPATH", "");
        pb.environment().put("HGPLAIN", "");
        pb.directory(repo.root().toFile());

        var stderr = Files.createTempFile("dump", ".stderr.txt");
        pb.redirectError(stderr.toFile());
        log.finer("hg dump stderr: " + stderr.toString());

        log.finer("Starting '" + String.join(" ", command) + "'");
        return new ProcessInfo(pb.start(), command, null, stderr, () -> Files.delete(ext));
    }

    private ProcessInfo pull(Repository repo, URI source) throws IOException {
        var ext = Files.createTempFile("ext", ".py");
        var extStream = getClass().getResourceAsStream("/ext.py");
        if (extStream == null) {
            // Used when running outside of the module path (such as from an IDE)
            var classPath = Path.of(getClass().getProtectionDomain().getCodeSource().getLocation().getPath());
            var extPath = classPath.getParent().resolve("resources").resolve("ext.py");
            extStream = new FileInputStream(extPath.toFile());
        }
        Files.copy(extStream, ext, StandardCopyOption.REPLACE_EXISTING);

        var hook = "hooks.pretxnclose=python:" + ext.toAbsolutePath().toString() + ":pretxnclose";
        var command = List.of("hg", "--config", hook, "pull", "--quiet", source.toString());
        var pb = new ProcessBuilder(command);
        pb.environment().put("HGRCPATH", "");
        pb.environment().put("HGPLAIN", "");
        pb.directory(repo.root().toFile());

        var stderr = Files.createTempFile("pull", ".stderr.txt");
        pb.redirectError(stderr.toFile());

        log.finer("Starting '" + String.join(" ", command) + "'");
        return new ProcessInfo(pb.start(), command, null, stderr, () -> Files.delete(ext));
    }

    private ProcessInfo fastImport(ReadOnlyRepository repo) throws IOException {
        var command = List.of("git", "fast-import", "--allow-unsafe-features");
        var pb = new ProcessBuilder(command);
        pb.directory(repo.root().toFile());

        var stdout = Files.createTempFile("fast-import", ".stdout.txt");
        pb.redirectOutput(stdout.toFile());

        var stderr = Files.createTempFile("fast-import", ".stderr.txt");
        pb.redirectError(stderr.toFile());

        log.finer("Starting '" + String.join(" ", command) + "'");
        return new ProcessInfo(pb.start(), command, stdout, stderr);
    }

    private void await(ProcessInfo p) throws IOException {
        try {
            int res = p.waitForProcess();
            if (res != 0) {
                var msg = String.join(" ", p.command()) + " exited with status " + res;
                log.severe(msg);
                throw new IOException(msg);
            }
        } catch (InterruptedException e) {
            throw new IOException(e);
        }
    }

    private void convert(ProcessInfo hg, ProcessInfo git, ReadOnlyRepository hgRepo, Path marks) throws IOException {
        var pipe = new Pipe(hg.process().getInputStream(), git.process().getOutputStream(), 512);

        pipe.println("feature done");
        pipe.println("feature import-marks-if-exists=" + marks.toAbsolutePath().toString());
        pipe.println("feature export-marks=" + marks.toAbsolutePath().toString());

        var tagCommits = convertCommits(pipe);
        convertTags(pipe, tagCommits, hgRepo);

        pipe.println("done");
    }

    private void log(ProcessInfo hg, ProcessInfo git, Path gitRoot) throws IOException {
        if (Files.exists(hg.stderr())) {
            var content = Files.readString(hg.stderr());
            if (!content.isEmpty()) {
                log.warning("'" + String.join(" ", hg.command()) + "' [stderr]: " + content);
            }
        }

        if (Files.exists(git.stdout())) {
            var content = Files.readString(git.stdout());
            if (!content.isEmpty()) {
                log.warning("'" + String.join(" ", git.command()) + "' [stdout]: " + content);
            }
        }
        if (Files.exists(git.stderr())) {
            var content = Files.readString(git.stderr());
            if (!content.isEmpty()) {
                log.warning("'" + String.join(" ", git.command()) + "' [stderr]: " + content);
            }
        }

        if (Files.isDirectory(gitRoot)) {
            for (var path : Files.walk(gitRoot).collect(Collectors.toList())) {
                if (path.getFileName().toString().startsWith("fast_import_crash")) {
                    log.warning(Files.readString(path));
                }
            }
        }
    }

    public List<Mark> convert(ReadOnlyRepository hgRepo, Repository gitRepo) throws IOException {
        try (var hg = dump(hgRepo);
             var git = fastImport(gitRepo)) {
            try {
                var gitMarks = Files.createTempFile("git", ".marks.txt");
                convert(hg, git, hgRepo, gitMarks);

                await(git);
                await(hg);

                var ret = readMarks(gitMarks);
                Files.delete(gitMarks);
                return ret;
            } catch (IOException e) {
                log(hg, git, gitRepo.root());
                throw e;
            }
        }
    }

    public List<Mark> pull(Repository hgRepo, URI source, Repository gitRepo, List<Mark> marks) throws IOException {
        try (var hg = pull(hgRepo, source);
             var git = fastImport(gitRepo)) {
            try {
                for (var mark : marks) {
                    hgHashesToMarks.put(mark.hg(), mark.key());
                    marksToHgHashes.put(mark.key(), mark.hg());
                    currentMark = Math.max(mark.key(), currentMark);
                }
                var gitMarks = writeMarks(marks);
                convert(hg, git, hgRepo, gitMarks);

                await(git);
                await(hg);

                var ret = readMarks(gitMarks);
                Files.delete(gitMarks);
                return ret;
            } catch (IOException e) {
                log(hg, git, gitRepo.root());
                throw e;
            }
        }
    }
}
