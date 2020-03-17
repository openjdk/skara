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

import org.openjdk.skara.email.EmailAddress;
import org.openjdk.skara.forge.*;
import org.openjdk.skara.issuetracker.IssueTracker;
import org.openjdk.skara.jcheck.JCheckConfiguration;
import org.openjdk.skara.network.URIBuilder;
import org.openjdk.skara.vcs.*;
import org.openjdk.skara.vcs.openjdk.Issue;
import org.openjdk.skara.version.Version;
import org.openjdk.skara.webrev.Webrev;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import java.util.stream.Collectors;

class WebrevStorage {
    private final HostedRepository storage;
    private final String storageRef;
    private final Path baseFolder;
    private final URI baseUri;
    private final EmailAddress author;
    private final Logger log = Logger.getLogger("org.openjdk.skara.bots.mlbridge");

    WebrevStorage(HostedRepository storage, String ref, Path baseFolder, URI baseUri, EmailAddress author) {
        this.baseFolder = baseFolder;
        this.baseUri = baseUri;
        this.storage = storage;
        storageRef = ref;
        this.author = author;
    }

    private void generate(PullRequest pr, Repository localRepository, Path folder, Diff diff, Hash base, Hash head) throws IOException {
        Files.createDirectories(folder);
        var fullName = pr.author().fullName();
        var builder = Webrev.repository(localRepository)
                            .output(folder)
                            .version(Version.fromManifest().orElse("unknown"))
                            .upstream(pr.repository().webUrl().toString())
                            .pullRequest(pr.webUrl().toString())
                            .username(fullName);

        var issue = Issue.fromString(pr.title());
        if (issue.isPresent()) {
            var files = localRepository.files(head, List.of(Path.of(".jcheck", "conf")));
            if (!files.isEmpty()) {
                var conf = JCheckConfiguration.from(localRepository, head);
                var project = conf.general().jbs() != null ? conf.general().jbs() : conf.general().project();
                var id = issue.get().id();
                var issueTracker = IssueTracker.from("jira", URI.create("https://bugs.openjdk.java.net"));
                var hostedIssue = issueTracker.project(project).issue(id);
                if (hostedIssue.isPresent()) {
                    builder = builder.issue(hostedIssue.get().webUrl().toString());
                }
            }
        }

        if (diff != null) {
            builder.generate(diff);
        } else {
            builder.generate(base, head);
        }
    }

    private String generatePlaceholder(PullRequest pr, Hash base, Hash head) {
        return "This file was too large to be included in the published webrev, and has been replaced with " +
                "this placeholder message. It is possible to generate the original content locally by " +
                "following these instructions:\n\n" +
                "  $ git fetch " + pr.repository().webUrl() + " " + pr.fetchRef() + "\n" +
                "  $ git checkout " + head.hex() + "\n" +
                "  $ git webrev -r " + base.hex() + "\n";
    }

    private void replaceContent(Path file, String placeholder) {
        try {
            if (file.getFileName().toString().endsWith(".html")) {
                var existing = Files.readString(file);
                var headerEnd = existing.indexOf("<pre>");
                var footerStart = existing.lastIndexOf("</pre>");
                if ((headerEnd > 0) && (footerStart > 0)) {
                    var header = existing.substring(0, headerEnd + 5);
                    var footer = existing.substring(footerStart);
                    Files.writeString(file, header + placeholder + footer);
                    return;
                }
            }
            Files.writeString(file, placeholder);
        } catch (IOException e) {
            throw new RuntimeException("Failed to replace large file with placeholder");
        }
    }

    private boolean shouldBeReplaced(Path file) {
        try {
            if (file.getFileName().toString().equals("index.html")) {
                return false;
            } else {
                return Files.size(file) >= 1000 * 1000;
            }
        } catch (IOException e) {
            return false;
        }
    }

    private void push(Repository localStorage, Path webrevFolder, String identifier, String placeholder) throws IOException {
        var batchIndex = new AtomicInteger();

        // Replace large files (except the index) with placeholders
        try (var files = Files.walk(webrevFolder)) {
            files.filter(Files::isRegularFile)
                 .filter(this::shouldBeReplaced)
                 .forEach(file -> replaceContent(file, placeholder));
        }

        // Try to push 1000 files at a time
        try (var files = Files.walk(webrevFolder)) {
            var batches = files.filter(Files::isRegularFile)
                               .collect(Collectors.groupingBy(path -> {
                                   int curIndex = batchIndex.incrementAndGet();
                                   return Math.floorDiv(curIndex, 1000);
                               }));

            for (var batch : batches.entrySet()) {
                localStorage.add(batch.getValue());
                Hash hash;
                var message = "Added webrev for " + identifier +
                        (batches.size() > 1 ? " (" + (batch.getKey() + 1) + "/" + batches.size() + ")" : "");
                try {
                    hash = localStorage.commit(message, author.fullName().orElseThrow(), author.address());
                } catch (IOException e) {
                    // If the commit fails, it probably means that we're resuming a partially completed previous update
                    // where some of the files have already been committed. Ignore it and continue.
                    continue;
                }
                localStorage.push(hash, storage.url(), storageRef);
            }
        }
    }

    private static void clearDirectory(Path directory) {
        try (var files = Files.walk(directory)) {
            files.map(Path::toFile)
                 .sorted(Comparator.reverseOrder())
                 .forEach(File::delete);
        } catch (IOException io) {
            throw new RuntimeException(io);
        }
    }

    private void awaitPublication(URI uri, Duration timeout) throws IOException {
        var end = Instant.now().plus(timeout);
        var uriBuilder = URIBuilder.base(uri);
        var client = HttpClient.newBuilder()
                               .connectTimeout(Duration.ofSeconds(30))
                               .build();
        while (Instant.now().isBefore(end)) {
            var uncachedUri = uriBuilder.setQuery(Map.of("nocache", UUID.randomUUID().toString())).build();
            log.fine("Validating webrev URL: " + uncachedUri);
            var request = HttpRequest.newBuilder(uncachedUri)
                                     .timeout(Duration.ofSeconds(30))
                                     .GET()
                                     .build();
            try {
                var response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() < 300) {
                    log.info(response.statusCode() + " when checking " + uncachedUri + " - success!");
                    return;
                }
                if (response.statusCode() < 400) {
                    var newLocation = response.headers().firstValue("location");
                    if (newLocation.isPresent()) {
                        log.info("Webrev url redirection: " + newLocation.get());
                        uriBuilder = URIBuilder.base(newLocation.get());
                        continue;
                    }
                }
                log.info(response.statusCode() + " when checking " + uncachedUri + " - waiting...");
                Thread.sleep(Duration.ofSeconds(10).toMillis());
            } catch (InterruptedException ignored) {
            }
        }

        throw new RuntimeException("No success response from " + uri + " within " + timeout);
    }

    private URI createAndArchive(PullRequest pr, Repository localRepository, Path scratchPath, Diff diff, Hash base, Hash head, String identifier) {
        try {
            var localStorage = Repository.materialize(scratchPath, storage.url(),
                                                      "+" + storageRef + ":mlbridge_webrevs");
            var relativeFolder = baseFolder.resolve(String.format("%s/webrev.%s", pr.id(), identifier));
            var outputFolder = scratchPath.resolve(relativeFolder);
            // If a previous operation was interrupted there may be content here already - overwrite if so
            if (Files.exists(outputFolder)) {
                clearDirectory(outputFolder);
            }
            generate(pr, localRepository, outputFolder, diff, base, head);
            var placeholder = generatePlaceholder(pr, base, head);
            if (!localStorage.isClean()) {
                push(localStorage, outputFolder, baseFolder.resolve(pr.id()).toString(), placeholder);
            }
            var uri = URIBuilder.base(baseUri).appendPath(relativeFolder.toString().replace('\\', '/')).build();
            awaitPublication(uri, Duration.ofMinutes(30));
            return uri;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    interface WebrevGenerator {
        WebrevDescription generate(Hash base, Hash head, String identifier, WebrevDescription.Type type);
        WebrevDescription generate(Diff diff, String identifier, WebrevDescription.Type type, String description);
    }

    WebrevGenerator generator(PullRequest pr, Repository localRepository, Path scratchPath) {
        return new WebrevGenerator() {
            @Override
            public WebrevDescription generate(Hash base, Hash head, String identifier, WebrevDescription.Type type) {
                var uri = createAndArchive(pr, localRepository, scratchPath, null, base, head, identifier);
                return new WebrevDescription(uri, type);
            }

            @Override
            public WebrevDescription generate(Diff diff, String identifier, WebrevDescription.Type type, String description) {
                var uri = createAndArchive(pr, localRepository, scratchPath, diff, diff.from(), diff.to(), identifier);
                return new WebrevDescription(uri, type, description);
            }
        };
    }
}
