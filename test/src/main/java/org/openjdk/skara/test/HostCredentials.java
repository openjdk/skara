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
import org.openjdk.skara.json.*;
import org.openjdk.skara.network.URIBuilder;
import org.openjdk.skara.proxy.HttpProxy;
import org.openjdk.skara.vcs.*;

import org.junit.jupiter.api.TestInfo;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.logging.Logger;

public class HostCredentials implements AutoCloseable {
    private final String testName;
    private final Credentials credentials;
    private final List<PullRequest> pullRequestsToBeClosed = new ArrayList<>();
    private final List<Issue> issuesToBeClosed = new ArrayList<>();
    private HostedRepository credentialsLock;
    private int nextHostIndex;

    private final Logger log = Logger.getLogger("org.openjdk.skara.test");

    private interface Credentials {
        Forge createRepositoryHost(int userIndex);
        IssueTracker createIssueHost(int userIndex);
        HostedRepository getHostedRepository(Forge host);
        IssueProject getIssueProject(IssueTracker host);
        String getNamespaceName();
        default void close() {}
    }

    private static class GitHubCredentials implements Credentials {
        private final JSONObject config;
        private final Path configDir;

        GitHubCredentials(JSONObject config, Path configDir) {
            this.config = config;
            this.configDir = configDir;
        }

        @Override
        public Forge createRepositoryHost(int userIndex) {
            var hostUri = URIBuilder.base(config.get("host").asString()).build();
            var apps = config.get("apps").asArray();
            var key = configDir.resolve(apps.get(userIndex).get("key").asString());
            try {
                var keyContents = Files.readString(key, StandardCharsets.UTF_8);
                var pat = new Credential(apps.get(userIndex).get("id").asString() + ";" +
                                                 apps.get(userIndex).get("installation").asString(),
                                         keyContents);
                return Forge.from("github", hostUri, pat, null);
            } catch (IOException e) {
                throw new RuntimeException("Cannot read private key: " + key);
            }
        }

        @Override
        public IssueTracker createIssueHost(int userIndex) {
            throw new RuntimeException("not implemented yet");
        }

        @Override
        public HostedRepository getHostedRepository(Forge host) {
            return host.repository(config.get("project").asString()).orElseThrow();
        }

        @Override
        public IssueProject getIssueProject(IssueTracker host) {
            return host.project(config.get("project").asString());
        }

        @Override
        public String getNamespaceName() {
            return config.get("namespace").asString();
        }
    }

    private static class GitLabCredentials implements Credentials {
        private final JSONObject config;

        GitLabCredentials(JSONObject config) {
            this.config = config;
        }

        @Override
        public Forge createRepositoryHost(int userIndex) {
            var hostUri = URIBuilder.base(config.get("host").asString()).build();
            var users = config.get("users").asArray();
            var pat = new Credential(users.get(userIndex).get("name").asString(),
                                              users.get(userIndex).get("pat").asString());
            return Forge.from("gitlab", hostUri, pat, null);
        }

        @Override
        public IssueTracker createIssueHost(int userIndex) {
            throw new RuntimeException("not implemented yet");
        }

        @Override
        public HostedRepository getHostedRepository(Forge host) {
            return host.repository(config.get("project").asString()).orElseThrow();
        }

        @Override
        public IssueProject getIssueProject(IssueTracker host) {
            return host.project(config.get("project").asString());
        }

        @Override
        public String getNamespaceName() {
            return config.get("namespace").asString();
        }
    }

    private static class JiraCredentials implements Credentials {
        private final JSONObject config;
        private final TestCredentials repoCredentials;

        JiraCredentials(JSONObject config) {
            this.config = config;
            this.repoCredentials = new TestCredentials();
        }

        @Override
        public Forge createRepositoryHost(int userIndex) {
            return repoCredentials.createRepositoryHost(userIndex);
        }

        @Override
        public IssueTracker createIssueHost(int userIndex) {
            var hostUri = URIBuilder.base(config.get("host").asString()).build();
            var users = config.get("users").asArray();
            var pat = new Credential(users.get(userIndex).get("name").asString(),
                                     users.get(userIndex).get("pat").asString());
            return IssueTracker.from("jira", hostUri, pat, config);
        }

        @Override
        public HostedRepository getHostedRepository(Forge host) {
            return repoCredentials.getHostedRepository(host);
        }

        @Override
        public IssueProject getIssueProject(IssueTracker host) {
            return host.project(config.get("project").asString());
        }

        @Override
        public String getNamespaceName() {
            return config.get("namespace").asString();
        }
    }

    private static class TestCredentials implements Credentials {
        private final List<TestHost> hosts = new ArrayList<>();
        private final List<HostUser> users = List.of(
                new HostUser(1, "user1", "User Number 1"),
                new HostUser(2, "user2", "User Number 2"),
                new HostUser(3, "user3", "User Number 3"),
                new HostUser(4, "user4", "User Number 4")
        );

        private TestHost createHost(int userIndex) {
            if (userIndex == 0) {
                hosts.add(TestHost.createNew(users));
            } else {
                hosts.add(TestHost.createFromExisting(hosts.get(0), userIndex));
            }
            return hosts.get(hosts.size() - 1);
        }

        @Override
        public Forge createRepositoryHost(int userIndex) {
            return createHost(userIndex);
        }

        @Override
        public IssueTracker createIssueHost(int userIndex) {
            return createHost(userIndex);
        }

        @Override
        public HostedRepository getHostedRepository(Forge host) {
            return host.repository("test").orElseThrow();
        }

        @Override
        public IssueProject getIssueProject(IssueTracker host) {
            return host.project("test");
        }

        @Override
        public String getNamespaceName() {
            return "test";
        }

        @Override
        public void close() {
            hosts.forEach(TestHost::close);
        }
    }

    private Credentials parseEntry(JSONObject entry, Path credentialsPath) {
        if (!entry.contains("type")) {
            throw new RuntimeException("Entry type not set");
        }

        switch (entry.get("type").asString()) {
            case "gitlab":
                return new GitLabCredentials(entry);
            case "github":
                return new GitHubCredentials(entry, credentialsPath);
            case "jira":
                return new JiraCredentials(entry);
            default:
                throw new RuntimeException("Unknown entry type: " + entry.get("type").asString());
        }
    }

    private Forge getRepositoryHost() {
        var host = credentials.createRepositoryHost(nextHostIndex);
        nextHostIndex++;
        return host;
    }

    private IssueTracker getIssueHost() {
        var host = credentials.createIssueHost(nextHostIndex);
        nextHostIndex++;
        return host;
    }

    public HostCredentials(TestInfo testInfo) throws IOException  {
        HttpProxy.setup();

        var credentialsFile = System.getProperty("credentials");
        testName = testInfo.getDisplayName();

        // If no credentials have been specified, use the test host implementation
        if (credentialsFile == null) {
            credentials = new TestCredentials();
        } else {
            var credentialsPath = Paths.get(credentialsFile);
            var credentialsData = Files.readAllBytes(credentialsPath);
            var credentialsJson = JSON.parse(new String(credentialsData, StandardCharsets.UTF_8));
            credentials = parseEntry(credentialsJson.asObject(), credentialsPath.getParent());
        }
    }

    private boolean getLock(HostedRepository repo) throws IOException {
        try (var tempFolder = new TemporaryDirectory()) {
            var repoFolder = tempFolder.path().resolve("lock");
            var lockFile = repoFolder.resolve("lock.txt");
            Repository localRepo;
            try {
                localRepo = Repository.materialize(repoFolder, repo.url(), "testlock");
            } catch (IOException e) {
                // If the branch does not exist, we'll try to create it
                localRepo = Repository.init(repoFolder, VCS.GIT);
            }

            if (Files.exists(lockFile)) {
                var currentLock = Files.readString(lockFile, StandardCharsets.UTF_8).strip();
                var lockTime = ZonedDateTime.parse(currentLock, DateTimeFormatter.ISO_DATE_TIME);
                if (lockTime.isBefore(ZonedDateTime.now().minus(Duration.ofMinutes(10)))) {
                    log.info("Stale lock encountered - overwriting it");
                } else {
                    log.info("Active lock encountered - waiting");
                    return false;
                }
            }

            // The lock either doesn't exist or is stale, try to grab it
            var lockHash = commitLock(localRepo);
            localRepo.push(lockHash, repo.url(), "testlock");
            log.info("Obtained credentials lock");

            // If no exception occurs (such as the push fails), we have obtained the lock
            return true;
        }
    }

    private void releaseLock(HostedRepository repo) throws IOException {
        try (var tempFolder = new TemporaryDirectory()) {
            var repoFolder = tempFolder.path().resolve("lock");
            var lockFile = repoFolder.resolve("lock.txt");
            Repository localRepo;
            localRepo = Repository.materialize(repoFolder, repo.url(), "testlock");
            localRepo.remove(lockFile);
            var lockHash = localRepo.commit("Unlock", "test", "test@test.test");
            localRepo.push(lockHash, repo.url(), "testlock");
        }
    }

    public Hash commitLock(Repository localRepo) throws IOException {
        var lockFile = localRepo.root().resolve("lock.txt");
        Files.writeString(lockFile, ZonedDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME), StandardCharsets.UTF_8);
        localRepo.add(lockFile);
        var lockHash = localRepo.commit("Lock", "test", "test@test.test");
        localRepo.branch(lockHash, "testlock");
        return lockHash;
    }

    public HostedRepository getHostedRepository() {
        var host = getRepositoryHost();
        var repo = credentials.getHostedRepository(host);

        while (credentialsLock == null) {
            try {
                if (getLock(repo)) {
                    credentialsLock = repo;
                }
            } catch (IOException e) {
                try {
                    Thread.sleep(Duration.ofSeconds(1).toMillis());
                } catch (InterruptedException ignored) {
                }
            }
        }
        return repo;
    }

    public IssueProject getIssueProject() {
        var host = getIssueHost();
        return credentials.getIssueProject(host);
    }

    public PullRequest createPullRequest(HostedRepository hostedRepository, String targetRef, String sourceRef, String title, boolean draft) {
        var pr = hostedRepository.createPullRequest(hostedRepository, targetRef, sourceRef, title, List.of("PR body"), draft);
        pullRequestsToBeClosed.add(pr);
        return pr;
    }

    public PullRequest createPullRequest(HostedRepository hostedRepository, String targetRef, String sourceRef, String title) {
        return createPullRequest(hostedRepository, targetRef, sourceRef, title, false);
    }

    public Issue createIssue(IssueProject issueProject, String title) {
        var issue = issueProject.createIssue(title, List.of(), Map.of());
        issuesToBeClosed.add(issue);
        return issue;
    }

    public CensusBuilder getCensusBuilder() {
        return CensusBuilder.create(credentials.getNamespaceName());
    }

    @Override
    public void close() {
        for (var pr : pullRequestsToBeClosed) {
            pr.setState(PullRequest.State.CLOSED);
        }
        for (var issue : issuesToBeClosed) {
            issue.setState(Issue.State.CLOSED);
        }
        if (credentialsLock != null) {
            try {
                releaseLock(credentialsLock);
                log.info("Released credentials lock for " + testName);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            credentialsLock = null;
        }

        credentials.close();
    }
}
