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
package org.openjdk.skara.bots.notify.mailinglist;

import org.openjdk.skara.bot.BotConfiguration;
import org.openjdk.skara.bots.notify.*;
import org.openjdk.skara.email.EmailAddress;
import org.openjdk.skara.json.JSONObject;
import org.openjdk.skara.mailinglist.MailingListServerFactory;
import org.openjdk.skara.network.URIBuilder;

import java.time.Duration;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class MailingListUpdaterFactory implements NotifierFactory {
    @Override
    public String name() {
        return "mailinglist";
    }

    @Override
    public Notifier create(BotConfiguration botConfiguration, JSONObject notifierConfiguration) {
        var smtp = notifierConfiguration.get("smtp").asString();
        var sender = EmailAddress.parse(notifierConfiguration.get("sender").asString());
        var archive = URIBuilder.base(notifierConfiguration.get("archive").asString()).build();
        var interval = notifierConfiguration.contains("interval") ? Duration.parse(notifierConfiguration.get("interval").asString()) : Duration.ofSeconds(1);
        var listServer = MailingListServerFactory.createMailmanServer(archive, smtp, interval);

        var recipient = notifierConfiguration.get("recipient").asString();
        var recipientAddress = EmailAddress.parse(recipient);

        var author = notifierConfiguration.contains("author") ? EmailAddress.parse(notifierConfiguration.get("author").asString()) : null;
        var allowedDomains = author == null ? Pattern.compile(notifierConfiguration.get("domains").asString()) : null;

        var mailingListUpdaterBuilder = MailingListUpdater.newBuilder()
                                                          .list(listServer.getList(recipient))
                                                          .recipient(recipientAddress)
                                                          .sender(sender)
                                                          .author(author)
                                                          .allowedAuthorDomains(allowedDomains);

        if (notifierConfiguration.contains("mode")) {
            MailingListUpdater.Mode mode;
            switch (notifierConfiguration.get("mode").asString()) {
                case "all":
                    mode = MailingListUpdater.Mode.ALL;
                    break;
                case "pr":
                    mode = MailingListUpdater.Mode.PR;
                    break;
                default:
                    throw new RuntimeException("Unknown mode");
            }
            mailingListUpdaterBuilder.mode(mode);
        }
        if (notifierConfiguration.contains("headers")) {
            mailingListUpdaterBuilder.headers(notifierConfiguration.get("headers").fields().stream()
                                                                   .collect(Collectors.toMap(JSONObject.Field::name,
                                                                           field -> field.value().asString())));
        }
        if (notifierConfiguration.contains("branchnames")) {
            mailingListUpdaterBuilder.includeBranch(notifierConfiguration.get("branchnames").asBoolean());
        }
        if (notifierConfiguration.contains("tags")) {
            mailingListUpdaterBuilder.reportNewTags(notifierConfiguration.get("tags").asBoolean());
        }
        if (notifierConfiguration.contains("branches")) {
            mailingListUpdaterBuilder.reportNewBranches(notifierConfiguration.get("branches").asBoolean());
        }
        if (notifierConfiguration.contains("builds")) {
            mailingListUpdaterBuilder.reportNewBuilds(notifierConfiguration.get("builds").asBoolean());
        }

        return mailingListUpdaterBuilder.build();
    }
}
