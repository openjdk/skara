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
import org.openjdk.skara.email.EmailAddress;
import org.openjdk.skara.json.*;
import org.openjdk.skara.mailinglist.MailingListServerFactory;
import org.openjdk.skara.network.URIBuilder;
import org.openjdk.skara.storage.StorageBuilder;
import org.openjdk.skara.vcs.Tag;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
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

        URI reviewIcon = null;
        if (specific.contains("reviews")) {
            if (specific.get("reviews").contains("icon")) {
                reviewIcon = URI.create(specific.get("reviews").get("icon").asString());
            }
        }
        URI commitIcon = null;
        if (specific.contains("commits")) {
            if (specific.get("commits").contains("icon")) {
                commitIcon = URI.create(specific.get("commits").get("icon").asString());
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
            if (repo.value().contains("json")) {
                var folder = repo.value().get("folder").asString();
                var build = repo.value().get("build").asString();
                var version = repo.value().get("version").asString();
                updaters.add(new JsonUpdater(Path.of(folder), version, build));
            }
            if (repo.value().contains("mailinglists")) {
                var email = specific.get("email").asObject();
                var smtp = email.get("smtp").asString();
                var sender = EmailAddress.parse(email.get("sender").asString());
                var archive = URIBuilder.base(email.get("archive").asString()).build();
                var interval = email.contains("interval") ? Duration.parse(email.get("interval").asString()) : Duration.ofSeconds(1);
                var listServer = MailingListServerFactory.createMailmanServer(archive, smtp, interval);

                for (var mailinglist : repo.value().get("mailinglists").asArray()) {
                    var recipient = mailinglist.get("recipient").asString();
                    var recipientAddress = EmailAddress.parse(recipient);

                    var author = mailinglist.contains("author") ? EmailAddress.parse(mailinglist.get("author").asString()) : null;
                    var allowedDomains = author == null ? Pattern.compile(mailinglist.get("domains").asString()) : null;

                    var mailingListUpdaterBuilder = MailingListUpdater.newBuilder()
                                                                      .list(listServer.getList(recipient))
                                                                      .recipient(recipientAddress)
                                                                      .sender(sender)
                                                                      .author(author)
                                                                      .allowedAuthorDomains(allowedDomains);

                    if (mailinglist.contains("mode")) {
                        var mode = MailingListUpdater.Mode.ALL;
                        switch (mailinglist.get("mode").asString()) {
                            case "pr":
                                mode = MailingListUpdater.Mode.PR;
                                break;
                            case "pr-only":
                                mode = MailingListUpdater.Mode.PR_ONLY;
                                break;
                            default:
                                throw new RuntimeException("Unknown mode");
                        }
                        mailingListUpdaterBuilder.mode(mode);
                    }
                    if (mailinglist.contains("headers")) {
                        mailingListUpdaterBuilder.headers(mailinglist.get("headers").fields().stream()
                                                                     .collect(Collectors.toMap(JSONObject.Field::name,
                                                                                               field -> field.value().asString())));
                    }
                    if (mailinglist.contains("branchnames")) {
                        mailingListUpdaterBuilder.includeBranch(mailinglist.get("branchnames").asBoolean());
                    }
                    if (mailinglist.contains("tags")) {
                        mailingListUpdaterBuilder.reportNewTags(mailinglist.get("tags").asBoolean());
                    }
                    if (mailinglist.contains("branches")) {
                        mailingListUpdaterBuilder.reportNewBranches(mailinglist.get("branches").asBoolean());
                    }
                    if (mailinglist.contains("builds")) {
                        mailingListUpdaterBuilder.reportNewBuilds(mailinglist.get("builds").asBoolean());
                    }
                    updaters.add(mailingListUpdaterBuilder.build());
                }
            }
            if (repo.value().contains("issues")) {
                var issuesConf = repo.value().get("issues");
                var issueProject = configuration.issueProject(issuesConf.get("project").asString());
                var issueUpdaterBuilder = IssueUpdater.newBuilder()
                                                      .issueProject(issueProject);

                if (issuesConf.contains("reviewlink")) {
                    issueUpdaterBuilder.reviewLink(issuesConf.get("reviewlink").asBoolean());
                }
                if (issuesConf.contains("commitlink")) {
                    issueUpdaterBuilder.commitLink(issuesConf.get("commitlink").asBoolean());
                }
                if (issuesConf.contains("fixversions")) {
                    issueUpdaterBuilder.setFixVersion(true);
                    issueUpdaterBuilder.fixVersions(issuesConf.get("fixversions").fields().stream()
                                                              .collect(Collectors.toMap(JSONObject.Field::name,
                                                                                        f -> f.value().asString())));
                }
                if (issuesConf.contains("pronly")) {
                    issueUpdaterBuilder.prOnly(issuesConf.get("pronly").asBoolean());
                }
                updaters.add(issueUpdaterBuilder.build());
                prUpdaters.add(issueUpdaterBuilder.build());
            }

            if (updaters.isEmpty()) {
                log.warning("No consumers configured for notify bot repository: " + repoName);
                continue;
            }

            var baseName = repo.value().contains("basename") ? repo.value().get("basename").asString() : configuration.repositoryName(repoName);

            var tagStorageBuilder = new StorageBuilder<Tag>(baseName + ".tags.txt")
                    .remoteRepository(databaseRepo, databaseRef, databaseName, databaseEmail, "Added tag for " + repoName);
            var branchStorageBuilder = new StorageBuilder<ResolvedBranch>(baseName + ".branches.txt")
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
