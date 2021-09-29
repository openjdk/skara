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
package org.openjdk.skara.webrev;

import org.openjdk.skara.vcs.*;
import org.openjdk.skara.json.JSON;

import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.function.Function;

import static java.nio.file.StandardOpenOption.*;

public class Webrev {
    private static final String ANCNAV_HTML = "navigation.html";
    private static final String ANCNAV_JS = "navigation.js";

    private static final String ICON = "nanoduke.ico";
    private static final String CSS = "style.css";

    private static final String INDEX = "index.html";

    private static final Logger log = Logger.getLogger("org.openjdk.skara.webrev");

    public static final Set<String> STATIC_FILES =
        Set.of(ANCNAV_HTML, ANCNAV_JS, ICON, CSS, INDEX);

    public static class RequiredBuilder {
        private final ReadOnlyRepository repository;

        RequiredBuilder(ReadOnlyRepository repository) {
            this.repository = repository;
        }

        public Builder output(Path path) {
            return new Builder(repository, path);
        }
    }

    public static class Builder {
        private final ReadOnlyRepository repository;
        private final Path output;
        private String title = "webrev";
        private String username;
        private URI upstreamURI;
        private String upstreamName;
        private URI forkURI;
        private String forkName;
        private String fork;
        private String pullRequest;
        private String branch;
        private String issue;
        private Function<String, String> issueLinker;
        private Function<String, String> commitLinker;
        private String version;
        private List<Path> files = List.of();
        private int similarity = 90;
        private boolean comments;

        Builder(ReadOnlyRepository repository, Path output) {
            this.repository = repository;
            this.output = output;
        }

        public Builder username(String username) {
            this.username = username;
            return this;
        }

        public Builder title(String title) {
            this.title = title;
            return this;
        }

        public Builder upstream(String name) {
            this.upstreamName = name;
            return this;
        }

        public Builder upstream(URI uri, String name) {
            this.upstreamURI = uri;
            this.upstreamName = name;
            return this;
        }

        public Builder fork(String name) {
            this.forkName = name;
            return this;
        }

        public Builder fork(URI uri, String name) {
            this.forkURI = uri;
            this.forkName = name;
            return this;
        }

        public Builder pullRequest(String pullRequest) {
            this.pullRequest = pullRequest;
            return this;
        }

        public Builder branch(String branch) {
            this.branch = branch;
            return this;
        }

        public Builder issue(String issue) {
            this.issue = issue;
            return this;
        }

        public Builder issueLinker(Function<String, String> issueLinker) {
            this.issueLinker = issueLinker;
            return this;
        }

        public Builder commitLinker(Function<String, String> commitLinker) {
            this.commitLinker = commitLinker;
            return this;
        }

        public Builder version(String version) {
            this.version = version;
            return this;
        }

        public Builder files(List<Path> files) {
            this.files = files;
            return this;
        }

        public Builder similarity(int similarity) {
            this.similarity = similarity;
            return this;
        }

        public Builder comments(boolean comments) {
            this.comments = comments;
            return this;
        }

        public void generate(Hash tailEnd) throws IOException {
            generate(tailEnd, null);
        }

        public void generate(Hash tailEnd, Hash head) throws IOException {
            var diff = head == null ?
                    repository.diff(tailEnd, files, similarity) :
                    repository.diff(tailEnd, head, files, similarity);
            generate(diff, tailEnd, head);
        }

        public void generateJSON(Hash tailEnd, Hash head) throws IOException {
            if (head == null) {
                head = repository.head();
            }
            var diff = repository.diff(tailEnd, head, files);
            generateJSON(diff, tailEnd, head);
        }

        public void generate(Diff diff) throws IOException {
            generate(diff, diff.from(), diff.to());
        }

        public void generateJSON(Diff diff) throws IOException {
            generateJSON(diff, diff.from(), diff.to());
        }

        private boolean hasMergeCommits(Hash tailEnd, Hash head) throws IOException {
            var commits = repository.commitMetadata(tailEnd, head);
            return commits.stream().anyMatch(CommitMetadata::isMerge);
        }

        private void generateJSON(Diff diff, Hash tailEnd, Hash head) throws IOException {
            if (head == null) {
                throw new IllegalArgumentException("Must supply a head hash");
            }
            if (upstreamURI == null) {
                throw new IllegalStateException("Must supply an URI to upstream repository");
            }
            if (upstreamName == null) {
                throw new IllegalStateException("Must supply a name for the upstream repository");
            }
            if (forkURI == null) {
                throw new IllegalStateException("Must supply an URI to fork repository");
            }
            if (forkName == null) {
                throw new IllegalStateException("Must supply a name for the fork repository");
            }

            Files.createDirectories(output);
            var metadata = JSON.object();
            var now = ZonedDateTime.now();
            metadata.put("created_at", now.format(DateTimeFormatter.ISO_INSTANT));

            var base = JSON.object();
            base.put("sha", tailEnd.hex());
            base.put("repo",
                JSON.object().put("html_url", upstreamURI.toString())
                             .put("full_name", upstreamName)
            );
            metadata.put("base", base);

            var headObj = JSON.object();
            headObj.put("sha", head.hex());
            headObj.put("repo",
                JSON.object().put("html_url", forkURI.toString())
                             .put("full_name", forkName)
            );
            metadata.put("head", headObj);

            var pathsPerCommit = new HashMap<Hash, List<Path>>();
            var comparison = JSON.object();
            var files = JSON.array();
            for (var patch : diff.patches()) {
                var file = JSON.object();
                Path filename = null;
                Path previousFilename = null;
                String status = null;
                if (patch.status().isModified()) {
                    status = "modified";
                    filename = patch.target().path().get();
                } else if (patch.status().isAdded()) {
                    status = "added";
                    filename = patch.target().path().get();
                } else if (patch.status().isDeleted()) {
                    status = "deleted";
                    filename = patch.source().path().get();
                } else if (patch.status().isCopied()) {
                    status = "copied";
                    filename = patch.target().path().get();
                    previousFilename = patch.source().path().get();
                } else if (patch.status().isRenamed()) {
                    status = "renamed";
                    filename = patch.target().path().get();
                    previousFilename = patch.source().path().get();
                } else {
                    throw new IllegalStateException("Unexpected status: " + patch.status());
                }

                file.put("filename", filename.toString());
                file.put("status", status);
                if (previousFilename != null) {
                    file.put("previous_filename", previousFilename.toString());
                }
                if (patch.isBinary()) {
                    file.put("binary", true);
                } else {
                    file.put("binary", false);
                    var textualPatch = patch.asTextualPatch();

                    file.put("additions", textualPatch.additions());
                    file.put("deletions", textualPatch.deletions());
                    file.put("changes", textualPatch.changes());

                    var sb = new StringBuilder();
                    for (var hunk : textualPatch.hunks()) {
                        sb.append(hunk.toString());
                    }
                    file.put("patch", sb.toString());
                }
                files.add(file);
                var commits = hasMergeCommits(tailEnd, head) ?
                    repository.commitMetadata(repository.rangeInclusive(tailEnd, head), List.of(filename)) :
                    repository.follow(filename, tailEnd, head);
                for (var commit : commits) {
                    if (!pathsPerCommit.containsKey(commit.hash())) {
                        pathsPerCommit.put(commit.hash(), new ArrayList<>());
                    }
                    pathsPerCommit.get(commit.hash()).add(filename);
                }
            }
            comparison.put("files", files);

            var commits = JSON.array();
            for (var commit : repository.commitMetadata(tailEnd, head)) {
                var c = JSON.object();
                c.put("sha", commit.hash().hex());
                c.put("commit",
                    JSON.object().put("message", String.join("\n", commit.message()))
                );
                var filesArray = JSON.array();
                for (var path : pathsPerCommit.getOrDefault(commit.hash(), List.of())) {
                    filesArray.add(JSON.object().put("filename", path.toString()));
                }
                c.put("files", filesArray);
                commits.add(c);
            }

            Files.writeString(output.resolve("metadata.json"), metadata.toString(), StandardCharsets.UTF_8);
            Files.writeString(output.resolve("comparison.json"), comparison.toString(), StandardCharsets.UTF_8);
            Files.writeString(output.resolve("commits.json"), commits.toString(), StandardCharsets.UTF_8);
        }

        private void generate(Diff diff, Hash tailEnd, Hash head) throws IOException {
            Files.createDirectories(output);

            copyResource(ANCNAV_HTML);
            copyResource(ANCNAV_JS);
            copyResource(CSS);
            copyResource(ICON);

            var patches = diff.patches();
            var patchFile = output.resolve(Path.of(title).getFileName().toString() + ".patch");
            if (files != null && !files.isEmpty()) {
                // Sort the patches according to how they are listed in the `files` list.
                var byTargetPath = new HashMap<Path, Patch>();
                var bySourcePath = new HashMap<Path, Patch>();
                for (var patch : patches) {
                    if (patch.target().path().isPresent()) {
                        byTargetPath.put(patch.target().path().get(), patch);
                    } else {
                        bySourcePath.put(patch.source().path().get(), patch);
                    }
                }

                var sorted = new ArrayList<Patch>();
                for (var file : files) {
                    if (byTargetPath.containsKey(file)) {
                        sorted.add(byTargetPath.get(file));
                    } else if (bySourcePath.containsKey(file)) {
                        sorted.add(bySourcePath.get(file));
                    } else {
                        log.warning("ignoring file not present in diff: " + file);
                    }
                }
                patches = sorted;
            }

            var modified = new ArrayList<Integer>();
            for (var i = 0; i < patches.size(); i++) {
                var patch = patches.get(i);
                if (patch.status().isModified() || patch.status().isRenamed() || patch.status().isCopied()) {
                    modified.add(i);
                }
            }

            var navigations = new LinkedList<Navigation>();
            for (var i = 0; i < modified.size(); i++) {
                Path prev = null;
                Path next = null;
                if (i != 0) {
                    prev = patches.get(modified.get(i - 1)).target().path().get();
                }
                if (i != modified.size() - 1) {
                    next = patches.get(modified.get(i + 1)).target().path().get();
                }
                navigations.addLast(new Navigation(prev, next));
            }

            var headHash = head == null ? repository.head() : head;
            var filesDesc = files.isEmpty() ? "" :
                            " for files " +
                            files.stream().map(Path::toString).collect(Collectors.joining(", "));
            log.fine("Generating webrev from " + tailEnd + " to " + headHash + filesDesc);

            var fileViews = new ArrayList<FileView>();
            var formatter = new MetadataFormatter(issueLinker);
            for (var patch : patches) {
                var status = patch.status();
                var path = status.isDeleted() ?
                    patch.source().path().get() :
                    patch.target().path().get();
                var commits = comments ? repository.commitMetadata(tailEnd, headHash, List.of(path)) : Collections.<CommitMetadata>emptyList();
                if (status.isModified() || status.isRenamed() || status.isCopied()) {
                    var nav = navigations.removeFirst();
                    fileViews.add(new ModifiedFileView(repository, tailEnd, head, commits, formatter, patch, output, nav));
                } else if (status.isAdded()) {
                    fileViews.add(new AddedFileView(repository, tailEnd, head, commits, formatter, patch, output));
                } else if (status.isDeleted()) {
                    fileViews.add(new RemovedFileView(repository, tailEnd, head, commits, formatter, patch, output));
                }
            }

            var total = fileViews.stream().map(FileView::stats).mapToInt(Stats::total).sum();
            var stats = new Stats(diff.totalStats(), total);

            var issueForWebrev = issue != null && issueLinker != null ? issueLinker.apply(issue) : null;
            var tailEndURL = commitLinker != null ? commitLinker.apply(tailEnd.hex()) : null;
            try (var w = Files.newBufferedWriter(output.resolve(INDEX))) {
                var index = new IndexView(fileViews,
                                          title,
                                          username,
                                          upstreamName,
                                          branch,
                                          pullRequest,
                                          issueForWebrev,
                                          version,
                                          tailEnd,
                                          tailEndURL,
                                          output.relativize(patchFile),
                                          stats);
                index.render(w);

            }

            try (var totalPatch = FileChannel.open(patchFile, CREATE, WRITE)) {
                for (var patch : patches) {
                    var originalPath = patch.status().isDeleted() ? patch.source().path() : patch.target().path();
                    var patchPath = output.resolve(originalPath.get().toString() + ".patch");

                    try (var patchFragment = FileChannel.open(patchPath, READ)) {
                        var size = patchFragment.size();
                        var n = 0;
                        while (n < size) {
                            n += patchFragment.transferTo(n, size, totalPatch);
                        }
                    }
                }
            }
        }

        private void copyResource(String name) throws IOException {
            var stream = this.getClass().getResourceAsStream("/" + name);
            if (stream == null) {
                Path classPath;
                try {
                    classPath = Path.of(getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
                } catch (URISyntaxException e) {
                    throw new IOException(e);
                }
                var extPath = classPath.getParent().resolve("resources").resolve(name);
                stream = new FileInputStream(extPath.toFile());
            }
            Files.copy(stream, output.resolve(name));
        }
    }

    public static RequiredBuilder repository(ReadOnlyRepository repository) {
        return new RequiredBuilder(repository);
    }

    static String relativeTo(Path from, Path to) {
        var relative = from.relativize(to);
        return relative.subpath(1, relative.getNameCount()).toString();
    }

    static String relativeToCSS(Path out, Path file) {
        return relativeTo(file, out.resolve(CSS));
    }

    static String relativeToIndex(Path out, Path file) {
        return relativeTo(out.resolve(INDEX), file);
    }

    static String relativeToAncnavHTML(Path out, Path file) {
        return relativeTo(file, out.resolve(ANCNAV_HTML));
    }

    static String relativeToAncnavJS(Path out, Path file) {
        return relativeTo(file, out.resolve(ANCNAV_JS));
    }
}
