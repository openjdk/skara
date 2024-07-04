/*
 * Copyright (c) 2019, 2024, Oracle and/or its affiliates. All rights reserved.
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
import org.openjdk.skara.json.*;
import org.openjdk.skara.network.RestRequest;
import org.openjdk.skara.vcs.*;

import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static org.openjdk.skara.issuetracker.jira.JiraProject.JEP_NUMBER;
import static org.openjdk.skara.issuetracker.jira.JiraProject.RESOLVED_IN_BUILD;

public class TestHost implements Forge, IssueTracker {
    /***
     * TestBackportEndpoint simulates the JBS custom endpoint for creating backports in JBS
     */
    private static class TestBackportEndpoint implements IssueTracker.CustomEndpoint, IssueTracker.CustomEndpointRequest {
        private final TestHost host;

        private JSONValue body;

        private TestBackportEndpoint(TestHost host) {
            this.host = host;
        }

        @Override
        public IssueTracker.CustomEndpointRequest post() {
            return this;
        }

        @Override
        public IssueTracker.CustomEndpointRequest body(JSONValue body) {
            this.body = body;
            return this;
        }

        @Override
        public IssueTracker.CustomEndpointRequest header(String value, String name) {
            // Not needed
            return this;
        }

        @Override
        public IssueTracker.CustomEndpointRequest onError(RestRequest.ErrorTransform transform) {
            // Not needed
            return this;
        }

        @Override
        public JSONValue execute() {
            if (body == null) {
                throw new IllegalStateException("Must set body");
            }

            // A TestHost can only handle a single project and since a backport
            // requires a primary issue to exist, then there must already exist
            // a project for the primary issue
            var project = host.data.issueProjects.entrySet().stream().findFirst().orElseThrow().getValue();
            var primary = project.issue(body.get("parentIssueKey").asString()).orElseThrow();

            var props = new HashMap<String, JSONValue>();
            props.put("issuetype", JSON.of("Backport"));
            // Propagate properties set in POST request body
            if (body.contains("level")) {
                props.put("security", body.get("level"));
            }
            if (body.contains("fixVersion")) {
                props.put("fixVersions", JSON.array().add(body.get("fixVersion")));
            }

            // Propagate properties from the primary issue *except* those
            // that can be set via the POST request body. The custom
            // RESOLVED_IN_BUILD property should also not propagate
            var ignore = Set.of("issuetype", "assignee", "security", "fixVersions", RESOLVED_IN_BUILD);
            for (var entry : primary.properties().entrySet()) {
                if (!ignore.contains(entry.getKey())) {
                    props.put(entry.getKey(), entry.getValue());
                }
            }

            var backport = project.createIssue(primary.title(), Arrays.asList(primary.body().split("\n")), props);
            if (body.contains("assignee")) {
                var user = host.user(body.get("assignee").asString()).orElseThrow();
                backport.setAssignees(List.of(user));
            }
            backport.addLink(Link.create(primary, "backport of").build());
            primary.addLink(Link.create(backport, "backported by").build());
            return JSON.object().put("key", backport.id());
        }
    }

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
    private Duration minTimeStampUpdateInterval = Duration.ZERO;
    // Setting this field doesn't change the behavior of the TestHost, but it changes
    // what the associated method returns, which triggers different code paths in
    // dependent test code.
    private Duration timeStampQueryPrecision = Duration.ofNanos(1);

    private static class HostData {
        final List<HostUser> users = new ArrayList<>();
        final Map<String, Repository> repositories = new HashMap<>();
        final Map<String, IssueProject> issueProjects = new HashMap<>();
        final Set<TemporaryDirectory> folders = new HashSet<>();
        private final Map<String, TestPullRequestStore> pullRequests = new HashMap<>();
        private final Map<String, TestIssueTrackerIssueStore> issues = new HashMap<>();
    }

    // Map of org to map of user to MemberState
    private final Map<String, Map<String, MemberState>> organizationMembers = new HashMap<>();

    private Repository createLocalRepository() {
        var folder = new TemporaryDirectory();
        data.folders.add(folder);
        try {
            var repo = TestableRepository.init(folder.path().resolve("hosted.git"), VCS.GIT);
            Files.writeString(repo.root().resolve("content.txt"), "Initial content");
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
    public Optional<IssueTracker.CustomEndpoint> lookupCustomEndpoint(String path) {
        switch (path) {
            case "/rest/jbs/1.0/backport/":
                return Optional.of(new TestBackportEndpoint(this));
            default:
                return Optional.empty();
        }
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
    public Optional<HostUser> userById(String id) {
        return data.users.stream()
                .filter(user -> user.id().equals(id))
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
    public Optional<String> search(Hash hash, boolean includeDiffs) {
        for (var key : data.repositories.keySet()) {
            var repo = repository(key).orElseThrow();
            var commit = repo.commit(hash, includeDiffs);
            if (commit.isPresent()) {
                return Optional.of(repo.name());
            }
        }
        return Optional.empty();
    }

    void close() {
        if (currentUser == 0) {
            data.folders.forEach(TemporaryDirectory::close);
        }
    }

    TestPullRequest createPullRequest(TestHostedRepository targetRepository, TestHostedRepository sourceRepository,
            String targetRef, String sourceRef, String title, List<String> body, boolean draft) {
        var id = String.valueOf(data.pullRequests.size() + 1);
        var prStore = new TestPullRequestStore(id, targetRepository.forge().currentUser(), title, body,
                sourceRepository, targetRef, sourceRef, draft);
        data.pullRequests.put(id, prStore);
        return new TestPullRequest(prStore, targetRepository);
    }

    TestPullRequest getPullRequest(TestHostedRepository repository, String id) {
        var store = data.pullRequests.get(id);
        return new TestPullRequest(store, repository);
    }

    List<TestPullRequest> getPullRequests(TestHostedRepository repository) {
        return data.pullRequests.entrySet().stream()
                                .sorted(Comparator.comparing(Map.Entry::getKey))
                                .map(pr -> getPullRequest(repository, pr.getKey()))
                                .collect(Collectors.toList());
    }

    TestIssueTrackerIssue createIssue(TestIssueProject issueProject, String title, List<String> body, Map<String, JSONValue> properties) {
        var id = issueProject.projectName().toUpperCase() + "-" + (data.issues.size() + 1);
        HostUser author = issueProject.issueTracker().currentUser();
        var issueStore = new TestIssueTrackerIssueStore(id, issueProject, author, title, body, properties);
        data.issues.put(id, issueStore);
        return new TestIssueTrackerIssue(issueStore, author);
    }

    TestIssueTrackerIssue getIssue(TestIssueProject issueProject, String id) {
        var issueStore = data.issues.get(id);
        if (issueStore == null) {
            return null;
        }
        return new TestIssueTrackerIssue(issueStore, issueProject.issueTracker().currentUser());
    }

    TestIssueTrackerIssue getJepIssue(TestIssueProject issueProject, String jepId) {
        var jepIssue = data.issues.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(issue -> getIssue(issueProject, issue.getKey()))
                .filter(issue -> {
                    var issueType = issue.properties().get("issuetype");
                    var jepNumber = issue.properties().get(JEP_NUMBER);
                    return issueType != null && "JEP".equals(issueType.asString()) &&
                           jepNumber != null && jepId.equals(jepNumber.asString());
                })
                .findFirst();
        return jepIssue.orElse(null);
    }

    List<TestIssueTrackerIssue> getIssues(TestIssueProject issueProject) {
        return data.issues.entrySet().stream()
                          .sorted(Comparator.comparing(Map.Entry::getKey))
                          .map(issue -> getIssue(issueProject, issue.getKey()))
                          .filter(i -> i.state().equals(Issue.State.OPEN))
                          .collect(Collectors.toList());
    }

    List<TestIssueTrackerIssue> getIssues(TestIssueProject issueProject, ZonedDateTime updatedAfter) {
        return data.issues.entrySet().stream()
                          .sorted(Map.Entry.comparingByKey())
                          .map(issue -> getIssue(issueProject, issue.getKey()))
                          .filter(i -> !i.updatedAt().isBefore(updatedAfter))
                          .collect(Collectors.toList());
    }

    List<TestIssueTrackerIssue> getCsrIssues(TestIssueProject issueProject, ZonedDateTime updatedAfter) {
        return data.issues.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(issue -> getIssue(issueProject, issue.getKey()))
                .filter(i -> {
                    var type = i.properties().get("issuetype");
                    return type != null && "CSR".equals(type.asString());
                })
                .filter(i -> !i.updatedAt().isBefore(updatedAfter))
                .collect(Collectors.toList());
    }

    Optional<TestIssueTrackerIssue> getLastUpdatedIssue(TestIssueProject issueProject) {
        return data.issues.keySet().stream()
                .map(testIssue -> getIssue(issueProject, testIssue))
                .max(Comparator.comparing(TestIssueTrackerIssue::updatedAt));
    }

    public void setMinTimeStampUpdateInterval(Duration minTimeStampUpdateInterval) {
        this.minTimeStampUpdateInterval = minTimeStampUpdateInterval;
    }

    @Override
    public Duration minTimeStampUpdateInterval() {
        return minTimeStampUpdateInterval;
    }

    public void setTimeStampQueryPrecision(Duration timeStampQueryPrecision) {
        this.timeStampQueryPrecision = timeStampQueryPrecision;
    }

    @Override
    public Duration timeStampQueryPrecision() {
        return timeStampQueryPrecision;
    }

    @Override
    public List<HostUser> groupMembers(String group) {
        return organizationMembers.getOrDefault(group, Map.of()).keySet().stream()
                .map(u -> user(u).orElseThrow())
                .toList();
    }

    @Override
    public void addGroupMember(String group, HostUser user) {
        organizationMembers.putIfAbsent(group, new HashMap<>());
        organizationMembers.get(group).put(user.username(), MemberState.PENDING);
    }

    /**
     * Test method to update an existing org member to active status
     */
    public void confirmGroupMember(String group, String user) {
        organizationMembers.get(group).put(user, MemberState.ACTIVE);
    }

    @Override
    public MemberState groupMemberState(String group, HostUser user) {
        return organizationMembers.getOrDefault(group, Map.of()).getOrDefault(user.username(), MemberState.MISSING);
    }

    /**
     * Test method to update the active state of an existing user
     */
    public void setUserActive(String user, boolean active) {
        var currentUser = user(user).orElseThrow();
        data.users.remove(currentUser);
        var newUser = HostUser.create(currentUser.id(), currentUser.username(), currentUser.fullName(), active);
        data.users.add(newUser);
    }
}
