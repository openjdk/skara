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

import java.time.Duration;
import org.openjdk.skara.forge.*;
import org.openjdk.skara.host.HostUser;
import org.openjdk.skara.issuetracker.*;
import org.openjdk.skara.json.JSONValue;
import org.openjdk.skara.vcs.*;

import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static org.openjdk.skara.issuetracker.jira.JiraProject.JEP_NUMBER;

public class TestHost implements Forge, IssueTracker {

    /**
     * If test needs to name a repository that should not exist on the TestHost,
     * use this as the name of the repository.
     */
    public static final String NON_EXISTING_REPO = "non-existing-repo";

    private final int currentUser;
    private HostData data;
    private final Logger log = Logger.getLogger("org.openjdk.skara.test");
    // Setting this field doesn't change the behavior of the TestHost, but it changes
    // what the associated method returns, which triggers different code paths in
    // dependent code for testing.
    private Duration minTimeStampUpdateInterval = Duration.ofMillis(0);
    // Makes queries for pull requests return copies to better simulate querying
    // from a remote server in certain tests.
    private boolean copyPullRequests = false;

    private static class HostData {
        final List<HostUser> users = new ArrayList<>();
        final Map<String, Repository> repositories = new HashMap<>();
        final Map<String, IssueProject> issueProjects = new HashMap<>();
        final Set<TemporaryDirectory> folders = new HashSet<>();
        private final Map<String, TestPullRequest> pullRequests = new HashMap<>();
        private final Map<String, TestIssue> issues = new HashMap<>();
    }

    private Repository createLocalRepository() {
        var folder = new TemporaryDirectory();
        data.folders.add(folder);
        try {
            var repo = TestableRepository.init(folder.path().resolve("hosted.git"), VCS.GIT);
            Files.writeString(repo.root().resolve("content.txt"), "Initial content", StandardCharsets.UTF_8);
            repo.add(repo.root().resolve("content.txt"));
            var hash = repo.commit("Initial content", "author", "author@none");
            repo.push(hash, repo.root().toUri(), "testhostcontent");
            repo.checkout(hash, true);
            return repo;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static TestHost createNew(List<HostUser> users) {
        var data = new HostData();
        data.users.addAll(users);
        var host = new TestHost(data, 0);
        return host;
    }

    static TestHost createFromExisting(TestHost existing, int userIndex) {
        var host = new TestHost(existing.data, userIndex);
        return host;
    }

    private TestHost(HostData data, int currentUser) {
        this.data = data;
        this.currentUser = currentUser;
    }

    @Override
    public boolean isValid() {
        return true;
    }

    @Override
    public String name() {
        return "Test";
    }

    @Override
    public String hostname() {
        return "test.test";
    }

    @Override
    public URI uri() {
        try {
            return new URI(hostname());
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Optional<HostedRepository> repository(String name) {
        Repository localRepository;
        if (NON_EXISTING_REPO.equals(name)) {
            return Optional.empty();
        }
        if (data.repositories.containsKey(name)) {
            localRepository = data.repositories.get(name);
        } else {
            localRepository = createLocalRepository();
            data.repositories.put(name, localRepository);
        }
        return Optional.of(new TestHostedRepository(this, name, localRepository));
    }

    @Override
    public IssueProject project(String name) {
        if (data.issueProjects.containsKey(name)) {
            return data.issueProjects.get(name);
        } else {
            if (data.issueProjects.size() > 0) {
                throw new RuntimeException("A test host can only manage a single issue project");
            }
            var issueProject = new TestIssueProject(this, name);
            data.issueProjects.put(name, issueProject);
            return issueProject;
        }
    }

    @Override
    public Optional<HostUser> user(String username) {
        return data.users.stream()
                         .filter(user -> user.username().equals(username))
                         .findAny();
    }

    @Override
    public HostUser currentUser() {
        return data.users.get(currentUser);
    }

    @Override
    public boolean isMemberOf(String groupId, HostUser user) {
        return false;
    }

    @Override
    public Optional<HostedCommit> search(Hash hash) {
        for (var key : data.repositories.keySet()) {
            var repo = repository(key).orElseThrow();
            var commit = repo.commit(hash);
            if (commit.isPresent()) {
                return commit;
            }
        }
        return Optional.empty();
    }

    void close() {
        if (currentUser == 0) {
            data.folders.forEach(TemporaryDirectory::close);
        }
    }

    TestPullRequest createPullRequest(TestHostedRepository targetRepository, TestHostedRepository sourceRepository, String targetRef, String sourceRef, String title, List<String> body, boolean draft) {
        var id = String.valueOf(data.pullRequests.size() + 1);
        var pr = TestPullRequest.createNew(targetRepository, sourceRepository, id, targetRef, sourceRef, title, body, draft);
        data.pullRequests.put(id, pr);
        return pr;
    }

    TestPullRequest getPullRequest(TestHostedRepository repository, String id) {
        var original = data.pullRequests.get(id);
        if (copyPullRequests) {
            return new TestPullRequest(original);
        } else {
            return TestPullRequest.createFrom(repository, original);
        }
    }

    List<TestPullRequest> getPullRequests(TestHostedRepository repository) {
        return data.pullRequests.entrySet().stream()
                                .sorted(Comparator.comparing(Map.Entry::getKey))
                                .map(pr -> getPullRequest(repository, pr.getKey()))
                                .collect(Collectors.toList());
    }

    TestIssue createIssue(TestIssueProject issueProject, String title, List<String> body, Map<String, JSONValue> properties) {
        var id = issueProject.projectName().toUpperCase() + "-" + (data.issues.size() + 1);
        var issue = TestIssue.createNew(issueProject, id, title, body, properties);
        data.issues.put(id ,issue);
        return issue;
    }

    TestIssue getIssue(TestIssueProject issueProject, String id) {
        var original = data.issues.get(id);
        if (original == null) {
            return null;
        }
        return TestIssue.createFrom(issueProject, original);
    }

    TestIssue getJepIssue(TestIssueProject issueProject, String jepId) {
        var jepIssue = data.issues.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(issue -> getIssue(issueProject, issue.getKey()))
                .filter(issue -> {
                    var issueType = issue.data.properties.get("issuetype");
                    var jepNumber = issue.data.properties.get(JEP_NUMBER);
                    return issueType != null && "JEP".equals(issueType.asString()) &&
                           jepNumber != null && jepId.equals(jepNumber.asString());
                })
                .findFirst();
        return jepIssue.orElse(null);
    }

    List<TestIssue> getIssues(TestIssueProject issueProject) {
        return data.issues.entrySet().stream()
                          .sorted(Comparator.comparing(Map.Entry::getKey))
                          .map(issue -> getIssue(issueProject, issue.getKey()))
                          .filter(i -> i.state().equals(Issue.State.OPEN))
                          .collect(Collectors.toList());
    }

    List<TestIssue> getIssues(TestIssueProject issueProject, ZonedDateTime updatedAfter) {
        return data.issues.entrySet().stream()
                          .sorted(Map.Entry.comparingByKey())
                          .map(issue -> getIssue(issueProject, issue.getKey()))
                          .filter(i -> i.updatedAt().isAfter(updatedAfter))
                          .collect(Collectors.toList());
    }

    List<TestIssue> getCsrIssues(TestIssueProject issueProject, ZonedDateTime updatedAfter) {
        return data.issues.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(issue -> getIssue(issueProject, issue.getKey()))
                .filter(i -> {
                    var type = i.properties().get("issuetype");
                    return type != null && "CSR".equals(type.asString());
                })
                .filter(i -> i.updatedAt().isAfter(updatedAfter))
                .collect(Collectors.toList());
    }

    Optional<TestIssue> getLastUpdatedIssue(TestIssueProject issueProject) {
        return data.issues.keySet().stream()
                .map(testIssue -> getIssue(issueProject, testIssue))
                .max(Comparator.comparing(TestIssue::updatedAt));
    }

    public void setMinTimeStampUpdateInterval(Duration minTimeStampUpdateInterval) {
        this.minTimeStampUpdateInterval = minTimeStampUpdateInterval;
    }

    @Override
    public Duration minTimeStampUpdateInterval() {
        return minTimeStampUpdateInterval;
    }

    public void setCopyPullRequests(boolean copyPullRequests) {
        this.copyPullRequests = copyPullRequests;
    }
}
