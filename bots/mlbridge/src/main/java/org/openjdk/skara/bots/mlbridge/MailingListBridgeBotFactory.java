/*
 * Copyright (c) 2019, 2022, Oracle and/or its affiliates. All rights reserved.
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

import java.net.URI;
import org.openjdk.skara.bot.*;
import org.openjdk.skara.email.EmailAddress;
import org.openjdk.skara.mailinglist.MailingListReader;
import org.openjdk.skara.mailinglist.MailingListServer;
import org.openjdk.skara.network.URIBuilder;
import org.openjdk.skara.json.*;
import org.openjdk.skara.mailinglist.MailingListServerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class MailingListBridgeBotFactory implements BotFactory {
    static final String NAME = "mlbridge";
    @Override
    public String name() {
        return NAME;
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
        String archiveType = null;
        if (specific.get("server").contains("type")) {
            archiveType = specific.get("server").get("type").asString();
        }
        var listSmtp = specific.get("server").get("smtp").asString();

        var webrevHTMLRepo = configuration.repository(specific.get("webrevs").get("repository").get("html").asString());
        var webrevJSONRepo = configuration.repository(specific.get("webrevs").get("repository").get("json").asString());
        var webrevRef = specific.get("webrevs").get("ref").asString();
        var webrevWeb = specific.get("webrevs").get("web").asString();

        var archiveRepo = configuration.repository(specific.get("archive").asString());
        var archiveRef = configuration.repositoryRef(specific.get("archive").asString());
        var globalIssueTracker = URIBuilder.base(specific.get("issues").asString()).build();

        var readyLabels = specific.get("ready").get("labels").stream()
                .map(JSONValue::asString)
                .collect(Collectors.toSet());
        var readyComments = specific.get("ready").get("comments").stream()
                .map(JSONValue::asObject)
                .collect(Collectors.toMap(obj -> obj.get("user").asString(),
                                          obj -> Pattern.compile(obj.get("pattern").asString())));
        var cooldown = specific.contains("cooldown") ? Duration.parse(specific.get("cooldown").asString()) : Duration.ofMinutes(1);
        boolean useEtag = false;
        if (specific.get("server").contains("etag")) {
            useEtag = specific.get("server").get("etag").asBoolean();
        }
        MailingListServer mailmanServer = createMailmanServer(archiveType, listArchive, listSmtp, useEtag);

        var mailingListReaderMap = new HashMap<List<String>, MailingListReader>();

        for (var repoConfig : specific.get("repositories").asArray()) {
            var repo = repoConfig.get("repository").asString();
            var hostedRepository = configuration.repository(repo);
            var censusRepo = configuration.repository(repoConfig.get("census").asString());
            var censusRef = configuration.repositoryRef(repoConfig.get("census").asString());

            var issueTracker = globalIssueTracker;
            if (repoConfig.contains("issues")) {
                issueTracker = URIBuilder.base(repoConfig.get("issues").asString()).build();
            }

            Map<String, String> headers = repoConfig.contains("headers") ?
                    repoConfig.get("headers").fields().stream()
                              .collect(Collectors.toMap(JSONObject.Field::name, field -> field.value().asString())) :
                    Map.of();

            var lists = parseLists(repoConfig.get("lists"));
            if (!repoConfig.contains("bidirectional") || repoConfig.get("bidirectional").asBoolean()) {
                var listNamesForReading = new HashSet<EmailAddress>();
                for (var list : lists) {
                    listNamesForReading.add(list.list());
                }
                var listsForReading = listNamesForReading.stream()
                                                         .map(EmailAddress::localPart)
                                                         .collect(Collectors.toList());

                // Reuse MailingListReaders with the exact same set of mailing lists between bots
                // to benefit more from cached results.
                if (!mailingListReaderMap.containsKey(listsForReading)) {
                    mailingListReaderMap.put(listsForReading, mailmanServer.getListReader(listsForReading.toArray(new String[0])));
                }
                var bot = new MailingListArchiveReaderBot(mailingListReaderMap.get(listsForReading), hostedRepository);
                ret.add(bot);
            }

            var folder = repoConfig.contains("folder") ? repoConfig.get("folder").asString() : configuration.repositoryName(repo);

            var webrevGenerateHTML = true;
            if (repoConfig.contains("webrevs") &&
                repoConfig.get("webrevs").contains("html") &&
                repoConfig.get("webrevs").get("html").asBoolean() == false) {
                webrevGenerateHTML = false;
            }
            var webrevGenerateJSON = repoConfig.contains("webrevs") &&
                                     repoConfig.get("webrevs").contains("json") &&
                                     repoConfig.get("webrevs").get("json").asBoolean();

            var botBuilder = MailingListBridgeBot.newBuilder().from(from)
                                                 .repo(hostedRepository)
                                                 .archive(archiveRepo)
                                                 .archiveRef(archiveRef)
                                                 .censusRepo(censusRepo)
                                                 .censusRef(censusRef)
                                                 .lists(lists)
                                                 .ignoredUsers(ignoredUsers)
                                                 .ignoredComments(ignoredComments)
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
                                                 .cooldown(cooldown)
                                                 .seedStorage(configuration.storageFolder().resolve("seeds"))
                                                 .mailingListServer(mailmanServer);

            if (repoConfig.contains("reponame")) {
                botBuilder.repoInSubject(repoConfig.get("reponame").asBoolean());
            }
            if (repoConfig.contains("branchname")) {
                botBuilder.branchInSubject(Pattern.compile(repoConfig.get("branchname").asString()));
            }
            ret.add(botBuilder.build());
        }

        return ret;
    }

    private static MailingListServer createMailmanServer(String archiveType, URI listArchive, String listSmtp, boolean useEtag) {
        MailingListServer mailmanServer;
        if (archiveType == null || archiveType.equals("mailman2")) {
            mailmanServer = MailingListServerFactory.createMailman2Server(listArchive, listSmtp, Duration.ZERO, useEtag);
        } else if (archiveType.equals("mailman3")) {
            mailmanServer = MailingListServerFactory.createMailman3Server(listArchive, listSmtp, Duration.ZERO, useEtag);
        } else {
            throw new RuntimeException("Invalid server archive type: " + archiveType);
        }
        return mailmanServer;
    }
}
