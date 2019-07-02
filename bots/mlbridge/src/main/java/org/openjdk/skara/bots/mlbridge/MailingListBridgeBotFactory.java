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
import org.openjdk.skara.host.HostedRepository;
import org.openjdk.skara.host.network.URIBuilder;
import org.openjdk.skara.json.*;
import org.openjdk.skara.mailinglist.MailingListServerFactory;

import java.nio.file.Path;
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
        var ignoredUsers = specific.get("ignored").stream()
                                   .map(JSONValue::asString)
                                   .collect(Collectors.toSet());
        var listArchive = URIBuilder.base(specific.get("server").get("archive").asString()).build();
        var listSmtp = specific.get("server").get("smtp").asString();

        var webrevRepo = specific.get("webrevs").get("repository").asString();
        var webrevRef = specific.get("webrevs").get("ref").asString();
        var webrevWeb = specific.get("webrevs").get("web").asString();

        var allListNames = new HashSet<EmailAddress>();
        var allRepositories = new HashSet<HostedRepository>();

        var readyLabels = specific.get("ready").get("labels").stream()
                .map(JSONValue::asString)
                .collect(Collectors.toSet());
        var readyComments = specific.get("ready").get("comments").stream()
                .map(JSONValue::asObject)
                .collect(Collectors.toMap(obj -> obj.get("user").asString(),
                                          obj -> Pattern.compile(obj.get("pattern").asString())));

        for (var repoConfig : specific.get("repositories").asArray()) {
            var repo = repoConfig.get("repository").asString();
            var archive = repoConfig.get("archive").asString();
            var list = EmailAddress.parse(repoConfig.get("list").asString());
            var bot = new MailingListBridgeBot(from, configuration.repository(repo), configuration.repository(archive),
                                               list, ignoredUsers, listArchive, listSmtp,
                                               configuration.repository(webrevRepo), webrevRef, Path.of(repo),
                                               URIBuilder.base(webrevWeb).build(), readyLabels, readyComments);
            ret.add(bot);

            allListNames.add(list);
            allRepositories.add(configuration.repository(repo));
        }

        var mailmanServer = MailingListServerFactory.createMailmanServer(listArchive, listSmtp);
        var allLists = allListNames.stream()
                                   .map(name -> mailmanServer.getList(name.toString()))
                                   .collect(Collectors.toSet());

        var bot = new MailingListArchiveReaderBot(from, allLists, allRepositories);
        ret.add(bot);

        return ret;
    }
}
