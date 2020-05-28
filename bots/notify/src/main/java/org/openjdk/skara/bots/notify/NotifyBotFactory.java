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
package org.openjdk.skara.bots.notify;

import org.openjdk.skara.bot.*;
import org.openjdk.skara.json.*;
import org.openjdk.skara.storage.StorageBuilder;

import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class NotifyBotFactory implements BotFactory {
    private final Logger log = Logger.getLogger("org.openjdk.skara.bots");;

    @Override
    public String name() {
        return "notify";
    }

    private JSONObject combineConfiguration(JSONObject global, JSONObject specific) {
        var ret = new JSONObject();
        if (global != null) {
            for (var globalField : global.fields()) {
                ret.put(globalField.name(), globalField.value());
            }
        }
        for (var specificField : specific.fields()) {
            ret.put(specificField.name(), specificField.value());
        }
        return ret;
    }

    @Override
    public List<Bot> create(BotConfiguration configuration) {
        var ret = new ArrayList<Bot>();
        var specific = configuration.specific();

        var database = specific.get("database").asObject();
        var databaseRepo = configuration.repository(database.get("repository").asString());
        var databaseRef = configuration.repositoryRef(database.get("repository").asString());
        var databaseName = database.get("name").asString();
        var databaseEmail = database.get("email").asString();

        var readyLabels = specific.get("ready").get("labels").stream()
                                  .map(JSONValue::asString)
                                  .collect(Collectors.toSet());
        var readyComments = specific.get("ready").get("comments").stream()
                                    .map(JSONValue::asObject)
                                    .collect(Collectors.toMap(obj -> obj.get("user").asString(),
                                                              obj -> Pattern.compile(obj.get("pattern").asString())));

        // Collect configuration applicable to all instances of a specific notifier
        var notifierFactories = NotifierFactory.getNotifierFactories();
        notifierFactories.forEach(notifierFactory -> log.info("Available notifier: " + notifierFactory.name()));
        var notifierConfiguration = new HashMap<String, JSONObject>();
        for (var notifierFactory : notifierFactories) {
            if (specific.contains(notifierFactory.name())) {
                notifierConfiguration.put(notifierFactory.name(), specific.get(notifierFactory.name()).asObject());
            }
        }

        for (var repo : specific.get("repositories").fields()) {
            var repoName = repo.name();
            var branchPattern = Pattern.compile("^master$");
            if (repo.value().contains("branches")) {
                branchPattern = Pattern.compile(repo.value().get("branches").asString());
            }

            var updaters = new ArrayList<RepositoryUpdateConsumer>();
            var prUpdaters = new ArrayList<PullRequestUpdateConsumer>();

            for (var notifierFactory : notifierFactories) {
                if (repo.value().contains(notifierFactory.name())) {
                    var confArray = repo.value().get(notifierFactory.name());
                    if (!confArray.isArray()) {
                        confArray = JSON.array().add(confArray);
                    }
                    for (var conf : confArray.asArray()) {
                        var finalConfiguration = combineConfiguration(notifierConfiguration.get(notifierFactory.name()), conf.asObject());
                        var notifier = Notifier.create(notifierFactory.name(), configuration, finalConfiguration);
                        log.info("Configuring notifier " + notifierFactory.name() + " for repository " + repoName);
                        if (notifier instanceof PullRequestUpdateConsumer) {
                            prUpdaters.add((PullRequestUpdateConsumer)notifier);
                        }
                        if (notifier instanceof RepositoryUpdateConsumer) {
                            updaters.add((RepositoryUpdateConsumer)notifier);
                        }
                    }
                }
            }

            if (updaters.isEmpty() && prUpdaters.isEmpty()) {
                log.warning("No notifiers configured for notify bot repository: " + repoName);
                continue;
            }

            var baseName = repo.value().contains("basename") ? repo.value().get("basename").asString() : configuration.repositoryName(repoName);

            var tagStorageBuilder = new StorageBuilder<UpdatedTag>(baseName + ".tags.txt")
                    .remoteRepository(databaseRepo, databaseRef, databaseName, databaseEmail, "Added tag for " + repoName);
            var branchStorageBuilder = new StorageBuilder<UpdatedBranch>(baseName + ".branches.txt")
                    .remoteRepository(databaseRepo, databaseRef, databaseName, databaseEmail, "Added branch hash for " + repoName);
            var issueStorageBuilder = new StorageBuilder<PullRequestIssues>(baseName + ".prissues.txt")
                    .remoteRepository(databaseRepo, databaseRef, databaseName, databaseEmail, "Added pull request issue info for " + repoName);
            var bot = NotifyBot.newBuilder()
                               .repository(configuration.repository(repoName))
                               .storagePath(configuration.storageFolder())
                               .branches(branchPattern)
                               .tagStorageBuilder(tagStorageBuilder)
                               .branchStorageBuilder(branchStorageBuilder)
                               .prIssuesStorageBuilder(issueStorageBuilder)
                               .updaters(updaters)
                               .prUpdaters(prUpdaters)
                               .readyLabels(readyLabels)
                               .readyComments(readyComments)
                               .build();
            ret.add(bot);
        }

        return ret;
    }
}
