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
package org.openjdk.skara.bots.mlbridge;

import org.openjdk.skara.bot.*;
import org.openjdk.skara.email.EmailAddress;
import org.openjdk.skara.forge.HostedRepository;
import org.openjdk.skara.network.URIBuilder;
import org.openjdk.skara.json.*;
import org.openjdk.skara.mailinglist.MailingListServerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class MailingListBridgeBotFactory implements BotFactory {
    @Override
    public String name() {
        return "mlbridge";
    }

    private MailingListConfiguration parseList(JSONObject configuration) {
        var listAddress = EmailAddress.parse(configuration.get("email").asString());
        Set<String> labels = configuration.contains("labels") ?
                configuration.get("labels").stream()
                             .map(JSONValue::asString)
                             .collect(Collectors.toSet()) :
                Set.of();
        return new MailingListConfiguration(listAddress, labels);
    }

    private List<MailingListConfiguration> parseLists(JSONValue configuration) {
        if (configuration.isArray()) {
            return configuration.stream()
                                .map(JSONValue::asObject)
                                .map(this::parseList)
                                .collect(Collectors.toList());
        } else {
            return List.of(parseList(configuration.asObject()));
        }
    }

    @Override
    public List<Bot> create(BotConfiguration configuration) {
        var ret = new ArrayList<Bot>();
        var specific = configuration.specific();

        var from = EmailAddress.from(specific.get("name").asString(), specific.get("mail").asString());
        var ignoredUsers = specific.get("ignored").get("users").stream()
                                   .map(JSONValue::asString)
                                   .collect(Collectors.toSet());
        var ignoredComments = specific.get("ignored").get("comments").stream()
                                      .map(JSONValue::asString)
                                      .map(pattern -> Pattern.compile(pattern, Pattern.MULTILINE | Pattern.DOTALL))
                                      .collect(Collectors.toSet());
        var listArchive = URIBuilder.base(specific.get("server").get("archive").asString()).build();
        var listSmtp = specific.get("server").get("smtp").asString();
        var interval = specific.get("server").contains("interval") ? Duration.parse(specific.get("server").get("interval").asString()) : Duration.ofSeconds(1);

        var webrevHTMLRepo = configuration.repository(specific.get("webrevs").get("repository").get("html").asString());
        var webrevJSONRepo = configuration.repository(specific.get("webrevs").get("repository").get("json").asString());
        var webrevRef = specific.get("webrevs").get("ref").asString();
        var webrevWeb = specific.get("webrevs").get("web").asString();

        var archiveRepo = configuration.repository(specific.get("archive").asString());
        var archiveRef = configuration.repositoryRef(specific.get("archive").asString());
        var issueTracker = URIBuilder.base(specific.get("issues").asString()).build();

        var listNamesForReading = new HashSet<EmailAddress>();
        var allRepositories = new HashSet<HostedRepository>();

        var readyLabels = specific.get("ready").get("labels").stream()
                .map(JSONValue::asString)
                .collect(Collectors.toSet());
        var readyComments = specific.get("ready").get("comments").stream()
                .map(JSONValue::asObject)
                .collect(Collectors.toMap(obj -> obj.get("user").asString(),
                                          obj -> Pattern.compile(obj.get("pattern").asString())));
        var cooldown = specific.contains("cooldown") ? Duration.parse(specific.get("cooldown").asString()) : Duration.ofMinutes(1);

        for (var repoConfig : specific.get("repositories").asArray()) {
            var repo = repoConfig.get("repository").asString();
            var censusRepo = configuration.repository(repoConfig.get("census").asString());
            var censusRef = configuration.repositoryRef(repoConfig.get("census").asString());

            Map<String, String> headers = repoConfig.contains("headers") ?
                    repoConfig.get("headers").fields().stream()
                              .collect(Collectors.toMap(JSONObject.Field::name, field -> field.value().asString())) :
                    Map.of();
            var lists = parseLists(repoConfig.get("lists"));
            var folder = repoConfig.contains("folder") ? repoConfig.get("folder").asString() : configuration.repositoryName(repo);

            var webrevGenerateHTML = true;
            if (repoConfig.contains("webrev") &&
                repoConfig.get("webrev").contains("html") &&
                repoConfig.get("webrev").get("html").asBoolean() == false) {
                webrevGenerateHTML = false;
            }
            var webrevGenerateJSON = repoConfig.contains("webrev") &&
                                     repoConfig.get("webrev").contains("json") &&
                                     repoConfig.get("webrev").get("json").asBoolean();

            var botBuilder = MailingListBridgeBot.newBuilder().from(from)
                                                 .repo(configuration.repository(repo))
                                                 .archive(archiveRepo)
                                                 .archiveRef(archiveRef)
                                                 .censusRepo(censusRepo)
                                                 .censusRef(censusRef)
                                                 .lists(lists)
                                                 .ignoredUsers(ignoredUsers)
                                                 .ignoredComments(ignoredComments)
                                                 .listArchive(listArchive)
                                                 .smtpServer(listSmtp)
                                                 .webrevStorageHTMLRepository(webrevHTMLRepo)
                                                 .webrevStorageJSONRepository(webrevJSONRepo)
                                                 .webrevStorageRef(webrevRef)
                                                 .webrevStorageBase(Path.of(folder))
                                                 .webrevStorageBaseUri(URIBuilder.base(webrevWeb).build())
                                                 .webrevGenerateHTML(webrevGenerateHTML)
                                                 .webrevGenerateJSON(webrevGenerateJSON)
                                                 .readyLabels(readyLabels)
                                                 .readyComments(readyComments)
                                                 .issueTracker(issueTracker)
                                                 .headers(headers)
                                                 .sendInterval(interval)
                                                 .cooldown(cooldown)
                                                 .seedStorage(configuration.storageFolder().resolve("seeds"));

            if (repoConfig.contains("reponame")) {
                botBuilder.repoInSubject(repoConfig.get("reponame").asBoolean());
            }
            if (repoConfig.contains("branchname")) {
                botBuilder.branchInSubject(Pattern.compile(repoConfig.get("branchname").asString()));
            }
            ret.add(botBuilder.build());

            if (!repoConfig.contains("bidirectional") || repoConfig.get("bidirectional").asBoolean()) {
                for (var list : lists) {
                    listNamesForReading.add(list.list());
                }
            }
            allRepositories.add(configuration.repository(repo));
        }

        var mailmanServer = MailingListServerFactory.createMailmanServer(listArchive, listSmtp, Duration.ZERO);
        var listsForReading = listNamesForReading.stream()
                                   .map(name -> mailmanServer.getList(name.toString()))
                                   .collect(Collectors.toSet());

        var bot = new MailingListArchiveReaderBot(from, listsForReading, allRepositories);
        ret.add(bot);

        return ret;
    }
}
