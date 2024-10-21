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
package org.openjdk.skara.bots.pr;

import org.openjdk.skara.bot.*;
import org.openjdk.skara.forge.*;
import org.openjdk.skara.issuetracker.IssueProject;
import org.openjdk.skara.json.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
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
        var repositories = new HashMap<IssueProject, List<HostedRepository>>();
        var repositoriesForCSR = new HashMap<IssueProject, List<HostedRepository>>();
        var pullRequestBotMap = new HashMap<String, PullRequestBot>();
        var issueProjectToIssuePRMapMap = new HashMap<IssueProject, Map<String, List<PRRecord>>>();

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
            IssueProject issueProject = null;
            if (repo.value().contains("issues")) {
                issueProject = configuration.issueProject(repo.value().get("issues").asString());
                botBuilder.issueProject(issueProject);
                repositories.putIfAbsent(issueProject, new ArrayList<>());
                repositories.get(issueProject).add(repository);
                issueProjectToIssuePRMapMap.putIfAbsent(issueProject, new ConcurrentHashMap<>());
                botBuilder.issuePRMap(issueProjectToIssuePRMapMap.get(issueProject));
            }
            if (repo.value().contains("useStaleReviews")) {
                botBuilder.useStaleReviews(repo.value().get("useStaleReviews").asBoolean());
            }
            if (repo.value().contains("acceptSimpleMerges")) {
                botBuilder.acceptSimpleMerges(repo.value().get("acceptSimpleMerges").asBoolean());
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
                var enableCsr = repo.value().get("csr").asBoolean();
                botBuilder.enableCsr(enableCsr);
                if (enableCsr && issueProject != null) {
                    repositoriesForCSR.putIfAbsent(issueProject, new ArrayList<>());
                    repositoriesForCSR.get(issueProject).add(repository);
                }
            }
            if (repo.value().contains("jep")) {
                botBuilder.enableJep(repo.value().get("jep").asBoolean());
            }
            if (repo.value().contains("merge")) {
                botBuilder.enableMerge(repo.value().get("merge").asBoolean());
            }
            if (repo.value().contains("backport")) {
                botBuilder.enableBackport(repo.value().get("backport").asBoolean());
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
            if (repo.value().contains("reviewMerge")) {
                MergePullRequestReviewConfiguration result = null;

                var val = repo.value().get("reviewMerge").asString().toLowerCase().trim();
                if (val.equals("always")) {
                    result = MergePullRequestReviewConfiguration.ALWAYS;
                } else if (val.equals("never")) {
                    result = MergePullRequestReviewConfiguration.NEVER;
                } else if (val.equals("jcheck")) {
                    result = MergePullRequestReviewConfiguration.JCHECK;
                } else {
                    throw new RuntimeException("Unexpected value for key \"reviewMerge\": '" +
                                               repo.value().get("reviewMerge") + "', " +
                                               "expected one of \"always\", \"never\" or \"jcheck\"");
                }

                botBuilder.reviewMerge(result);
            }
            if (repo.value().contains("processPR")) {
                botBuilder.processPR(repo.value().get("processPR").asBoolean());
            }
            if (repo.value().contains("processCommit")) {
                botBuilder.processCommit(repo.value().get("processCommit").asBoolean());
            }
            if (repo.value().contains("jcheckMerge")) {
                botBuilder.jcheckMerge(repo.value().get("jcheckMerge").asBoolean());
            }

            if (repo.value().contains("mergeSources")) {
                var mergeSources = repo.value().get("mergeSources").stream()
                        .map(JSONValue::asString)
                        .collect(Collectors.toSet());
                botBuilder.mergeSources(mergeSources);
            }

            if (repo.value().contains("approval")) {
                var approvalJSON = repo.value().get("approval");
                String prefix = approvalJSON.contains("prefix") ? approvalJSON.get("prefix").asString() : "";
                String request = approvalJSON.get("request").asString();
                String approved = approvalJSON.get("approved").asString();
                String rejected = approvalJSON.get("rejected").asString();
                String documentLink = approvalJSON.get("documentLink").asString();
                // default value is true
                boolean approvalComment = !approvalJSON.contains("approvalComment") || approvalJSON.get("approvalComment").asBoolean();
                //default value is maintainer approval
                String approvalTerm = approvalJSON.contains("approvalTerm") ? approvalJSON.get("approvalTerm").asString() : "maintainer approval";
                Approval approval = new Approval(prefix, request, approved, rejected, documentLink, approvalComment, approvalTerm);
                if (approvalJSON.contains("branches")) {
                    for (var branch : approvalJSON.get("branches").fields()) {
                        approval.addBranchPrefix(Pattern.compile(branch.name()), branch.value().get("prefix").asString());
                    }
                }
                botBuilder.approval(approval);
            }

            if (repo.value().contains("versionMismatchWarning")) {
                botBuilder.versionMismatchWarning(repo.value().get("versionMismatchWarning").asBoolean());
            }

            if (repo.value().contains("cleanCommandEnabled")) {
                botBuilder.cleanCommandEnabled(repo.value().get("cleanCommandEnabled").asBoolean());
            }

            if (repo.value().contains("checkContributorStatusForBackportCommand")) {
                botBuilder.checkContributorStatusForBackportCommand(repo.value().get("checkContributorStatusForBackportCommand").asBoolean());
            }

            var prBot = botBuilder.build();
            pullRequestBotMap.put(repository.name(), prBot);
            ret.add(prBot);
        }

        // Create a CSRIssueBot for each issueProject which associated with at least one csr enabled repository
        for (var issueProject : repositoriesForCSR.keySet()) {
            ret.add(0, new CSRIssueBot(issueProject, repositoriesForCSR.get(issueProject), pullRequestBotMap,
                    issueProjectToIssuePRMapMap.get(issueProject)));
        }

        // Create an IssueBot for each issueProject
        for (var issueProject : issueProjectToIssuePRMapMap.keySet()) {
            ret.add(0, new IssueBot(issueProject, repositories.get(issueProject), pullRequestBotMap,
                    issueProjectToIssuePRMapMap.get(issueProject)));
        }

        return ret;
    }
}
