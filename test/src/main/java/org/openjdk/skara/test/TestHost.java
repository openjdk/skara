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
import org.openjdk.skara.host.*;
import org.openjdk.skara.issuetracker.*;
import org.openjdk.skara.json.JSONValue;
import org.openjdk.skara.vcs.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

public class TestHost implements Forge, IssueTracker {
    private final int currentUser;
    private HostData data;

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
            var repo = Repository.init(folder.path().resolve("hosted.git"), VCS.GIT);
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
    public Optional<HostedRepository> repository(String name) {
        Repository localRepository;
        if (data.repositories.containsKey(name)) {
            localRepository = data.repositories.get(name);
        } else {
            if (data.repositories.size() > 0) {
                throw new RuntimeException("A test host can only manage a single repository");
            }
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
        return data.users
                   .stream()
                   .filter(user -> user.userName().equals(username))
                   .map(user -> Optional.of(user))
                   .findAny()
                   .orElseThrow();
    }

    @Override
    public HostUser currentUser() {
        return data.users.get(currentUser);
    }

    @Override
    public boolean supportsReviewBody() {
        return true;
    }

    @Override
    public boolean isMemberOf(String groupId, HostUser user) {
        return false;
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
        return TestPullRequest.createFrom(repository, original);
    }

    List<TestPullRequest> getPullRequests(TestHostedRepository repository) {
        return data.pullRequests.entrySet().stream()
                                .sorted(Comparator.comparing(Map.Entry::getKey))
                                .map(pr -> getPullRequest(repository, pr.getKey()))
                                .filter(pr -> pr.state().equals(Issue.State.OPEN))
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

    List<TestIssue> getIssues(TestIssueProject issueProject) {
        return data.issues.entrySet().stream()
                          .sorted(Comparator.comparing(Map.Entry::getKey))
                          .map(issue -> getIssue(issueProject, issue.getKey()))
                          .filter(i -> i.state().equals(Issue.State.OPEN))
                          .collect(Collectors.toList());
    }
}
