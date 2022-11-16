/*
 * Copyright (c) 2019, 2022, Oracle and/or its affiliates. All rights reserved.
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
import org.openjdk.skara.host.HostUser;
import org.openjdk.skara.issuetracker.Issue;
import org.openjdk.skara.issuetracker.Label;
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
    private final Map<Hash, List<CommitComment>> commitComments;
    private Map<String, Boolean> collaborators = new HashMap<>();
    private List<Label> labels = new ArrayList<>();
    private final Set<Check> checks = new HashSet<>();

    public TestHostedRepository(TestHost host, String projectName, Repository localRepository) {
        super(host, projectName);
        this.host = host;
        this.projectName = projectName;
        this.localRepository = localRepository;
        pullRequestPattern = Pattern.compile(url().toString() + "/pr/" + "(\\d+)");
        commitComments = new HashMap<>();
    }

    /**
     * Creates an instance without a backing local repository that will not support any actual repository interaction
     */
    public TestHostedRepository(String projectName) {
        super(null, projectName);
        this.host = null;
        this.projectName = projectName;
        this.localRepository = null;
        pullRequestPattern = null;
        commitComments = new HashMap<>();
    }

    /**
     * Creates an instance without a backing local repository that will not support any actual repository interaction
     */
    public TestHostedRepository(TestHost host, String projectName) {
        super(host, projectName);
        this.host = host;
        this.projectName = projectName;
        this.localRepository = null;
        pullRequestPattern = null;
        commitComments = new HashMap<>();
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
    public List<PullRequest> openPullRequests() {
        return host.getPullRequests(this).stream()
                   .filter(pr -> pr.state().equals(Issue.State.OPEN))
                   .collect(Collectors.toList());
    }

    @Override
    public List<PullRequest> pullRequestsAfter(ZonedDateTime updatedAfter) {
        return host.getPullRequests(this).stream()
                   .filter(pr -> !pr.updatedAt().isBefore(updatedAfter))
                   .sorted(Comparator.comparing(PullRequest::updatedAt).reversed())
                   .collect(Collectors.toList());
    }

    @Override
    public List<PullRequest> openPullRequestsAfter(ZonedDateTime updatedAfter) {
        return host.getPullRequests(this).stream()
                .filter(pr -> pr.state().equals(Issue.State.OPEN))
                .filter(pr -> !pr.updatedAt().isBefore(updatedAfter))
                .collect(Collectors.toList());
    }

    @Override
    public List<PullRequest> findPullRequestsWithComment(String author, String body) {
        return openPullRequests().stream()
                             .filter(pr -> pr.comments().stream()
                                                .filter(comment -> author == null || comment.author().username().equals(author))
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
    public URI nonTransformedWebUrl() {
        return url();
    }

    @Override
    public URI webUrl(Hash hash) {
        return URI.create(url().toString() + "/" + hash.hex());
    }

    @Override
    public URI webUrl(String baseRef, String headRef) {
        return URI.create(url().toString() + "/" + baseRef + "..." + headRef);
    }

    @Override
    public URI diffUrl(String prId) {
        return webUrl();
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

    @Override
    public List<HostedBranch> branches() {
        try {
            var result = new ArrayList<HostedBranch>();
            for (var b : localRepository.branches()) {
                result.add(new HostedBranch(b.name(), localRepository.resolve(b).orElseThrow()));
            }
            return result;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void deleteBranch(String ref) {
        try {
            localRepository.delete(new Branch(ref));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public List<CommitComment> commitComments(Hash hash) {
        if (!commitComments.containsKey(hash)) {
            return List.of();
        }
        return commitComments.get(hash);
    }

    @Override
    public List<CommitComment> recentCommitComments(Map<String, Set<Hash>> commitTitleToCommits, Set<Integer> excludeAuthors) {
        return commitComments.values()
                             .stream()
                             .flatMap(e -> e.stream())
                             .sorted((c1, c2) -> c2.updatedAt().compareTo(c1.updatedAt()))
                             .filter(c -> !excludeAuthors.contains(Integer.valueOf(c.author().id())))
                             .collect(Collectors.toList());
    }

    @Override
    public CommitComment addCommitComment(Hash hash, String body) {
        var createdAt = ZonedDateTime.now();
        var id = createdAt.toInstant().toString();

        if (!commitComments.containsKey(hash)) {
            commitComments.put(hash, new ArrayList<>());
        }
        var comments = commitComments.get(hash);
        var comment = new CommitComment(hash, null, -1, id, body, host.currentUser(), createdAt, createdAt);
        comments.add(comment);
        return comment;
    }

    @Override
    public void updateCommitComment(String id, String body) {
        throw new RuntimeException("not implemented yet");
    }

    @Override
    public Optional<HostedCommit> commit(Hash hash) {
        try {
            var commit = localRepository.lookup(hash);
            if (!commit.isPresent()) {
                return Optional.empty();
            }
            var url = URI.create("file://" + localRepository.root() + "/commits/" + hash.hex());
            return Optional.of(new HostedCommit(commit.get().metadata(), commit.get().parentDiffs(), url));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public List<Check> allChecks(Hash hash) {
        return checks.stream()
                .filter(check -> check.hash().equals(hash))
                .toList();
    }

    public void createCheck(Check check) {
        var existing = checks.stream()
                .filter(c -> c.name().equals(check.name()))
                .findAny();
        existing.ifPresent(checks::remove);
        checks.add(check);
    }

    @Override
    public WorkflowStatus workflowStatus() {
        return WorkflowStatus.ENABLED;
    }

    @Override
    public URI createPullRequestUrl(HostedRepository target, String sourceRef, String targetRef) {
        return URI.create(target.webUrl().toString() + "/pull/new/" + targetRef + "..." + projectName + ":" + sourceRef);
    }

    @Override
    public URI webUrl(Branch branch) {
        return URI.create(webUrl() + "/branch/" + branch.name());
    }

    @Override
    public URI webUrl(Tag tag) {
        return URI.create(webUrl() + "/tag/" + tag.name());
    }

    @Override
    public void addCollaborator(HostUser user, boolean canPush) {
        collaborators.put(user.username(), canPush);
    }

    @Override
    public boolean canPush(HostUser user) {
        return collaborators.getOrDefault(user.username(), false);
    }

    @Override
    public void restrictPushAccess(Branch branch, HostUser user) {
        // Not possible to simulate
    }

    Repository localRepository() {
        return localRepository;
    }

    @Override
    public List<Label> labels() {
        return labels;
    }

    @Override
    public void addLabel(Label label) {
        labels.add(label);
    }

    @Override
    public void updateLabel(Label label) {
        var existingLabel = labels.stream().filter(l -> l.name().equals(label.name())).findAny();
        existingLabel.ifPresent(value -> labels.remove(value));
        labels.add(label);
    }

    @Override
    public void deleteLabel(Label label) {
        var existingLabel = labels.stream().filter(l -> l.name().equals(label.name())).findAny();
        existingLabel.ifPresent(value -> labels.remove(value));
    }
}
