/*
 * Copyright (c) 2019, 2023, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.bots.pr;

import org.openjdk.skara.bot.*;
import org.openjdk.skara.forge.*;
import org.openjdk.skara.issuetracker.IssueProject;
import org.openjdk.skara.json.*;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class PullRequestBotFactory implements BotFactory {
    static final String NAME = "pr";
    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<Bot> create(BotConfiguration configuration) {
        var ret = new ArrayList<Bot>();
        var specific = configuration.specific();
        var issueProjects = new HashMap<String, IssueProject>();
        var repositories = new HashMap<IssueProject, List<HostedRepository>>();
        var pullRequestBotMap = new HashMap<String, PullRequestBot>();

        var externalPullRequestCommands = new HashMap<String, String>();
        if (specific.contains("external") && specific.get("external").contains("pr")) {
            for (var command : specific.get("external").get("pr").fields()) {
                externalPullRequestCommands.put(command.name(), command.value().asString());
            }
        }

        var externalCommitCommands = new HashMap<String, String>();
        if (specific.contains("external") && specific.get("external").contains("commit")) {
            for (var command : specific.get("external").get("commit").fields()) {
                externalCommitCommands.put(command.name(), command.value().asString());
            }
        }

        var blockers = new HashMap<String, String>();
        if (specific.contains("blockers")) {
            for (var blocker : specific.get("blockers").fields()) {
                blockers.put(blocker.name(), blocker.value().asString());
            }
        }

        var forks = new HashMap<String, HostedRepository>();
        if (specific.contains("forks")) {
            for (var fork : specific.get("forks").fields()) {
                var repo = configuration.repository(fork.value().asString());
                var upstream = configuration.repository(fork.name());
                forks.put(upstream.name(), repo);
            }
        }

        var mlbridgeBotName = "";
        if (specific.contains("mlbridge")) {
            mlbridgeBotName = specific.get("mlbridge").asString();
        }

        var excludeCommitCommentsFrom = new HashSet<Integer>();
        if (specific.contains("exclude-commit-comments-from")) {
            specific.get("exclude-commit-comments-from")
                    .stream()
                    .map(o -> o.asInt())
                    .forEach(id -> excludeCommitCommentsFrom.add(id));
        }

        var readyLabels = specific.get("ready").get("labels").stream()
                                  .map(JSONValue::asString)
                                  .collect(Collectors.toSet());
        var readyComments = specific.get("ready").get("comments").stream()
                                    .map(JSONValue::asObject)
                                    .collect(Collectors.toMap(obj -> obj.get("user").asString(),
                                                              obj -> Pattern.compile(obj.get("pattern").asString())));

        var labelConfigurations = new HashMap<String, LabelConfiguration>();
        for (var labelGroup : specific.get("labels").fields()) {
            if (labelGroup.value().contains("repository")) {
                var repository = configuration.repository(labelGroup.value().get("repository").asString());
                var ref = configuration.repositoryRef(labelGroup.value().get("repository").asString());
                var filename = labelGroup.value().get("filename").asString();
                labelConfigurations.put(labelGroup.name(),
                                        LabelConfigurationHostedRepository.from(repository, ref, filename));
            } else {
                labelConfigurations.put(labelGroup.name(),
                                        LabelConfigurationJson.from(labelGroup.value()));
            }
        }

        for (var repo : specific.get("repositories").fields()) {
            var censusRepo = configuration.repository(repo.value().get("census").asString());
            var censusRef = configuration.repositoryRef(repo.value().get("census").asString());
            var repository = configuration.repository(repo.name());
            var botBuilder = PullRequestBot.newBuilder()
                                           .repo(repository)
                                           .censusRepo(censusRepo)
                                           .censusRef(censusRef)
                                           .blockingCheckLabels(blockers)
                                           .readyLabels(readyLabels)
                                           .readyComments(readyComments)
                                           .externalPullRequestCommands(externalPullRequestCommands)
                                           .externalCommitCommands(externalCommitCommands)
                                           .seedStorage(configuration.storageFolder().resolve("seeds"))
                                           .excludeCommitCommentsFrom(excludeCommitCommentsFrom)
                                           .forks(forks)
                                           .mlbridgeBotName(mlbridgeBotName);

            if (repo.value().contains("labels")) {
                var labelGroup = repo.value().get("labels").asString();
                if (!labelConfigurations.containsKey(labelGroup)) {
                    throw new RuntimeException("Unknown label group: " + labelGroup);
                }
                botBuilder.labelConfiguration(labelConfigurations.get(labelGroup));
            }
            if (repo.value().contains("two-reviewers")) {
                var labels = repo.value().get("two-reviewers")
                                         .stream()
                                         .map(label -> label.asString())
                                         .collect(Collectors.toSet());
                botBuilder.twoReviewersLabels(labels);
            }
            if (repo.value().contains("24h")) {
                var labels = repo.value().get("24h")
                                         .stream()
                                         .map(label -> label.asString())
                                         .collect(Collectors.toSet());
                botBuilder.twentyFourHoursLabels(labels);
            }
            if (repo.value().contains("issues")) {
                var issueString = repo.value().get("issues").asString();
                botBuilder.issueProject(configuration.issueProject(issueString));
                var issueProject = issueProjects.get(issueString);
                if (issueProject == null) {
                    issueProject = configuration.issueProject(issueString);
                    issueProjects.put(issueString, issueProject);
                }
                if (!repositories.containsKey(issueProject)) {
                    repositories.put(issueProject, new ArrayList<>());
                }
                repositories.get(issueProject).add(repository);
            }
            if (repo.value().contains("ignorestale")) {
                botBuilder.ignoreStaleReviews(repo.value().get("ignorestale").asBoolean());
            }
            if (repo.value().contains("targetbranches")) {
                botBuilder.allowedTargetBranches(repo.value().get("targetbranches").asString());
            }
            if (repo.value().contains("jcheck")) {
                botBuilder.confOverrideRepo(configuration.repository(repo.value().get("jcheck").get("repo").asString()));
                botBuilder.confOverrideRef(configuration.repositoryRef(repo.value().get("jcheck").get("repo").asString()));
                if (repo.value().get("jcheck").contains("name")) {
                    botBuilder.confOverrideName(repo.value().get("jcheck").get("name").asString());
                }
            }
            if (repo.value().contains("censuslink")) {
                botBuilder.censusLink(repo.value().get("censuslink").asString());
            }
            if (repo.value().contains("csr")) {
                botBuilder.enableCsr(repo.value().get("csr").asBoolean());
            }
            if (repo.value().contains("jep")) {
                botBuilder.enableJep(repo.value().get("jep").asBoolean());
            }
            if (repo.value().contains("integrators")) {
                var integrators = repo.value().get("integrators")
                        .stream()
                        .map(JSONValue::asString)
                        .collect(Collectors.toSet());
                botBuilder.integrators(integrators);
            }
            if (repo.value().contains("reviewCleanBackport")) {
                botBuilder.reviewCleanBackport(repo.value().get("reviewCleanBackport").asBoolean());
            }
            var prBot = botBuilder.build();
            pullRequestBotMap.put(repository.name(), prBot);
            ret.add(prBot);
        }

        for (IssueProject issueProject : issueProjects.values()) {
            ret.add(new CSRIssueBot(issueProject, repositories.get(issueProject), pullRequestBotMap));
        }

        return ret;
    }
}
