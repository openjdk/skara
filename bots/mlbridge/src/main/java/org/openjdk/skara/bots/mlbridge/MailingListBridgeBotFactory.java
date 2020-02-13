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

        var webrevRepo = configuration.repository(specific.get("webrevs").get("repository").asString());
        var webrevRef = configuration.repositoryRef(specific.get("webrevs").get("repository").asString());
        var webrevWeb = specific.get("webrevs").get("web").asString();

        var archiveRepo = configuration.repository(specific.get("archive").asString());
        var archiveRef = configuration.repositoryRef(specific.get("archive").asString());
        var issueTracker = URIBuilder.base(specific.get("issues").asString()).build();

        var allListNames = new HashSet<EmailAddress>();
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

            var list = EmailAddress.parse(repoConfig.get("list").asString());
            var folder = repoConfig.contains("folder") ? repoConfig.get("folder").asString() : configuration.repositoryName(repo);
            var bot = MailingListBridgeBot.newBuilder().from(from)
                                          .repo(configuration.repository(repo))
                                          .archive(archiveRepo)
                                          .archiveRef(archiveRef)
                                          .censusRepo(censusRepo)
                                          .censusRef(censusRef)
                                          .list(list)
                                          .ignoredUsers(ignoredUsers)
                                          .ignoredComments(ignoredComments)
                                          .listArchive(listArchive)
                                          .smtpServer(listSmtp)
                                          .webrevStorageRepository(webrevRepo)
                                          .webrevStorageRef(webrevRef)
                                          .webrevStorageBase(Path.of(folder))
                                          .webrevStorageBaseUri(URIBuilder.base(webrevWeb).build())
                                          .readyLabels(readyLabels)
                                          .readyComments(readyComments)
                                          .issueTracker(issueTracker)
                                          .headers(headers)
                                          .sendInterval(interval)
                                          .cooldown(cooldown)
                                          .seedStorage(configuration.storageFolder().resolve("seeds"))
                                          .build();
            ret.add(bot);

            allListNames.add(list);
            allRepositories.add(configuration.repository(repo));
        }

        var mailmanServer = MailingListServerFactory.createMailmanServer(listArchive, listSmtp, Duration.ZERO);
        var allLists = allListNames.stream()
                                   .map(name -> mailmanServer.getList(name.toString()))
                                   .collect(Collectors.toSet());

        var bot = new MailingListArchiveReaderBot(from, allLists, allRepositories);
        ret.add(bot);

        return ret;
    }
}
