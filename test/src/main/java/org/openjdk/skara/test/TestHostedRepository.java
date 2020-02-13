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
package org.openjdk.skara.test;

import org.openjdk.skara.forge.*;
import org.openjdk.skara.json.JSONValue;
import org.openjdk.skara.vcs.*;

import java.io.*;
import java.net.*;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class TestHostedRepository extends TestIssueProject implements HostedRepository {
    private final TestHost host;
    private final String projectName;
    private final Repository localRepository;
    private final Pattern pullRequestPattern;

    public TestHostedRepository(TestHost host, String projectName, Repository localRepository) {
        super(host, projectName);
        this.host = host;
        this.projectName = projectName;
        this.localRepository = localRepository;
        pullRequestPattern = Pattern.compile(url().toString() + "/pr/" + "(\\d+)");
    }

    @Override
    public Forge forge() {
        return host;
    }

    @Override
    public Optional<HostedRepository> parent() {
        throw new RuntimeException("Not implemented yet");
    }

    @Override
    public PullRequest createPullRequest(HostedRepository target, String targetRef, String sourceRef, String title, List<String> body, boolean draft) {
        return host.createPullRequest((TestHostedRepository) target, this, targetRef, sourceRef, title, body, draft);
    }

    @Override
    public PullRequest pullRequest(String id) {
        return host.getPullRequest(this, id);
    }

    @Override
    public List<PullRequest> pullRequests() {
        return new ArrayList<>(host.getPullRequests(this));
    }

    @Override
    public List<PullRequest> pullRequests(ZonedDateTime updatedAfter) {
        return host.getPullRequests(this).stream()
                   .filter(pr -> pr.updatedAt().isAfter(updatedAfter))
                   .sorted(Comparator.comparing(PullRequest::updatedAt).reversed())
                   .collect(Collectors.toList());
    }

    @Override
    public List<PullRequest> findPullRequestsWithComment(String author, String body) {
        return pullRequests().stream()
                             .filter(pr -> pr.comments().stream()
                                                .filter(comment -> author == null || comment.author().userName().equals(author))
                                                .filter(comment -> comment == null ||comment.body().contains(body))
                                                .count() > 0
                                )
                             .collect(Collectors.toList());
    }

    @Override
    public Optional<PullRequest> parsePullRequestUrl(String url) {
        var matcher = pullRequestPattern.matcher(url);
        if (matcher.find()) {
            return Optional.of(pullRequest(matcher.group(1)));
        } else {
            return Optional.empty();
        }
    }

    @Override
    public String name() {
        return projectName;
    }

    @Override
    public URI url() {
        try {
            // We need a URL without a trailing slash
            var fileName = localRepository.root().getFileName().toString();
            return new URI(localRepository.root().getParent().toUri().toString() + fileName);
        } catch (IOException | URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public URI webUrl() {
        return url();
    }

    @Override
    public URI webUrl(Hash hash) {
        try {
            return new URI(url().toString() + "/" + hash.hex());
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public VCS repositoryType() {
        return VCS.GIT;
    }

    @Override
    public String fileContents(String filename, String ref) {
        try {
            var lines = localRepository.lines(Path.of(filename), localRepository.resolve(ref).orElseThrow());
            return String.join("\n", lines.orElseThrow());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public String namespace() {
        return "test";
    }

    @Override
    public Optional<WebHook> parseWebHook(JSONValue body) {
        return Optional.empty();
    }

    @Override
    public HostedRepository fork() {
        throw new RuntimeException("not implemented yet");
    }

    @Override
    public long id() {
        return 0L;
    }

    @Override
    public Hash branchHash(String ref) {
        try {
            var hash = localRepository.resolve(ref).orElseThrow();
            return hash;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    Repository localRepository() {
        return localRepository;
    }
}
