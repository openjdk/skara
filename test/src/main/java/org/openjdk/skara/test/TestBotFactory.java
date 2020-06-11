/*
 * Copyright (c) 2020, Oracle and/or its affiliates. All rights reserved.
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

import org.openjdk.skara.bot.*;
import org.openjdk.skara.ci.ContinuousIntegration;
import org.openjdk.skara.forge.HostedRepository;
import org.openjdk.skara.issuetracker.IssueProject;
import org.openjdk.skara.json.*;

import java.nio.file.Path;
import java.util.*;

public class TestBotFactory {
    private final Map<String, HostedRepository> hostedRepositories;
    private final Map<String, IssueProject> issueProjects;
    private final Path storagePath;
    private final JSONObject defaultConfiguration;

    private TestBotFactory(Map<String, HostedRepository> hostedRepositories, Map<String, IssueProject> issueProjects,
            Path storagePath, JSONObject defaultConfiguration) {
        this.hostedRepositories = Collections.unmodifiableMap(hostedRepositories);
        this.issueProjects = Collections.unmodifiableMap(issueProjects);
        this.storagePath = storagePath;
        this.defaultConfiguration = defaultConfiguration;
    }

    public static TestBotFactoryBuilder newBuilder() {
        return new TestBotFactoryBuilder();
    }

    public static class TestBotFactoryBuilder {
        private final Map<String, HostedRepository> hostedRepositories = new HashMap<>();
        private final Map<String, IssueProject> issueProjects = new HashMap<>();
        private final JSONObject defaultConfiguration = JSON.object();
        private Path storagePath;

        private TestBotFactoryBuilder() {
        }

        public TestBotFactoryBuilder addHostedRepository(String name, HostedRepository hostedRepository) {
            hostedRepositories.put(name, hostedRepository);
            return this;
        }

        public TestBotFactoryBuilder addIssueProject(String name, IssueProject issueProject) {
            issueProjects.put(name, issueProject);
            return this;
        }

        public TestBotFactoryBuilder addConfiguration(String field, JSONValue value) {
            defaultConfiguration.put(field, value);
            return this;
        }

        public TestBotFactoryBuilder storagePath(Path storagePath) {
            this.storagePath = storagePath;
            return this;
        }

        public TestBotFactory build() {
            return new TestBotFactory(hostedRepositories, issueProjects, storagePath, defaultConfiguration);
        }
    }

    public Bot create(String name, JSONObject configuration) {
        var finalConfiguration = JSON.object();
        for (var defaultField : defaultConfiguration.fields()) {
            finalConfiguration.put(defaultField.name(), defaultField.value());
        }
        for (var field : configuration.fields()) {
            finalConfiguration.put(field.name(), field.value());
        }

        var botConfiguration = new BotConfiguration() {
            @Override
            public Path storageFolder() {
                return storagePath;
            }

            @Override
            public HostedRepository repository(String name) {
                var repoName = name.split(":")[0];
                if (!hostedRepositories.containsKey(repoName)) {
                    throw new RuntimeException("Unknown repository: " + repoName);
                }
                return hostedRepositories.get(repoName);
            }

            @Override
            public IssueProject issueProject(String name) {
                if (!issueProjects.containsKey(name)) {
                    throw new RuntimeException("Unknown issue project: " + name);
                }
                return issueProjects.get(name);
            }

            @Override
            public ContinuousIntegration continuousIntegration(String name) {
                throw new RuntimeException("not implemented yet");
            }

            @Override
            public String repositoryRef(String name) {
                return name.split(":")[1];
            }

            @Override
            public String repositoryName(String name) {
                var refIndex = name.indexOf(':');
                if (refIndex >= 0) {
                    name = name.substring(0, refIndex);
                }
                var orgIndex = name.lastIndexOf('/');
                if (orgIndex >= 0) {
                    name = name.substring(orgIndex + 1);
                }
                return name;
            }

            @Override
            public JSONObject specific() {
                return finalConfiguration;
            }
        };

        var factories = BotFactory.getBotFactories();
        for (var factory : factories) {
            if (factory.name().equals(name)) {
                var bots = factory.create(botConfiguration);
                if (bots.size() != 1) {
                    throw new RuntimeException("Factory did not create a bot instance");
                }
                return bots.get(0);
            }
        }
        throw new RuntimeException("Failed to find bot factory with name: " + name);
    }
}
