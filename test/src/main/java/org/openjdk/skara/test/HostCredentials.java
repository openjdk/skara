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

import org.openjdk.skara.host.*;
import org.openjdk.skara.host.network.URIBuilder;
import org.openjdk.skara.json.*;

import org.junit.jupiter.api.TestInfo;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.logging.Logger;

public class HostCredentials implements AutoCloseable {
    private final String testName;
    private final Credentials credentials;
    private final Path credentialsLock;
    private final List<PullRequest> pullRequestsToBeClosed = new ArrayList<>();
    private boolean hasCredentialsLock;
    private int nextHostIndex;

    private final Logger log = Logger.getLogger("org.openjdk.skara.test");

    private interface Credentials {
        Host createNewHost(int userIndex);
        HostedRepository getHostedRepository(Host host);
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
        public Host createNewHost(int userIndex) {
            var hostUri = URIBuilder.base(config.get("host").asString()).build();
            var apps = config.get("apps").asArray();
            var key = configDir.resolve(apps.get(userIndex).get("key").asString());
            return HostFactory.createGitHubHost(hostUri,
                                                null,
                                                null,
                                                key.toString(),
                                                apps.get(userIndex).get("id").asString(),
                                                apps.get(userIndex).get("installation").asString());
        }

        @Override
        public HostedRepository getHostedRepository(Host host) {
            return host.getRepository(config.get("project").asString());
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
        public Host createNewHost(int userIndex) {
            var hostUri = URIBuilder.base(config.get("host").asString()).build();
            var users = config.get("users").asArray();
            var pat = new PersonalAccessToken(users.get(userIndex).get("name").asString(),
                                              users.get(userIndex).get("pat").asString());
            return HostFactory.createGitLabHost(hostUri, pat);
        }

        @Override
        public HostedRepository getHostedRepository(Host host) {
            return host.getRepository(config.get("project").asString());
        }

        @Override
        public String getNamespaceName() {
            return config.get("namespace").asString();
        }
    }

    private static class TestCredentials implements Credentials {
        private final List<TestHost> hosts = new ArrayList<>();
        private final List<HostUserDetails> users = List.of(
                new HostUserDetails(1, "user1", "User Number 1"),
                new HostUserDetails(2, "user2", "User Number 2"),
                new HostUserDetails(3, "user3", "User Number 3"),
                new HostUserDetails(4, "user4", "User Number 4")
        );

        @Override
        public Host createNewHost(int userIndex) {
            if (userIndex == 0) {
                hosts.add(TestHost.createNew(users));
            } else {
                hosts.add(TestHost.createFromExisting(hosts.get(0), userIndex));
            }
            return hosts.get(hosts.size() - 1);
        }

        @Override
        public HostedRepository getHostedRepository(Host host) {
            return host.getRepository("test");
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
            default:
                throw new RuntimeException("Unknown entry type: " + entry.get("type").asString());
        }
    }

    private Host getHost() {
        var host = credentials.createNewHost(nextHostIndex);
        nextHostIndex++;
        return host;
    }

    public HostCredentials(TestInfo testInfo) throws IOException  {
        var credentialsFile = System.getProperty("credentials");
        testName = testInfo.getDisplayName();

        // If no credentials have been specified, use the test host implementation
        if (credentialsFile == null) {
            credentials = new TestCredentials();
            credentialsLock = null;
        } else {
            credentialsLock = Path.of(credentialsFile + ".lock");

            var credentialsPath = Paths.get(credentialsFile);
            var credentialsData = Files.readAllBytes(credentialsPath);
            var credentialsJson = JSON.parse(new String(credentialsData, StandardCharsets.UTF_8));
            credentials = parseEntry(credentialsJson.asObject(), credentialsPath.getParent());
        }
    }

    public HostedRepository getHostedRepository() {
        if (credentialsLock != null && !hasCredentialsLock) {
            var tmpLock = Path.of(credentialsLock + "." + testName + ".tmp");
            try {
                Files.writeString(tmpLock, testName);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }

            while (!hasCredentialsLock) {
                try {
                    Files.move(tmpLock, credentialsLock);
                    log.info("Obtained credentials lock for " + testName);
                    hasCredentialsLock = true;
                } catch (IOException e) {
                    log.fine("Failed to obtain credentials lock for " + testName + ", waiting...");
                    try {
                        Thread.sleep(Duration.ofSeconds(1).toMillis());
                    } catch (InterruptedException ignored) {
                    }
                }
            }
        }

        var host = getHost();
        return credentials.getHostedRepository(host);
    }

    public PullRequest createPullRequest(HostedRepository hostedRepository, String targetRef, String sourceRef, String title) {
        var pr = hostedRepository.createPullRequest(hostedRepository, targetRef, sourceRef, title, List.of());
        pullRequestsToBeClosed.add(pr);
        return pr;
    }

    public CensusBuilder getCensusBuilder() {
        return CensusBuilder.create(credentials.getNamespaceName());
    }

    @Override
    public void close() {
        for (var pr : pullRequestsToBeClosed) {
            pr.setState(PullRequest.State.CLOSED);
        }
        if (credentialsLock != null && hasCredentialsLock) {
            try {
                Files.delete(credentialsLock);
                log.info("Released credentials lock for " + testName);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            hasCredentialsLock = false;
        }

        credentials.close();
    }
}
