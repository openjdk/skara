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
import org.openjdk.skara.json.JSONValue;
import org.openjdk.skara.storage.StorageBuilder;
import org.openjdk.skara.vcs.*;

import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class JNotifyBotFactory implements BotFactory {
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
        var databaseRef = database.get("ref").asString();
        var databaseName = database.get("name").asString();
        var databaseEmail = database.get("email").asString();

        for (var repo : specific.get("repositories").fields()) {
            var repoName = repo.name();
            var branches = repo.value().get("branches").stream()
                               .map(JSONValue::asString)
                               .collect(Collectors.toList());
            var build = repo.value().get("build").asString();
            var version = repo.value().get("version").asString();

            var updaters = new ArrayList<UpdateConsumer>();
            if (repo.value().contains("jsonfolder")) {
                updaters.add(new JsonUpdater(Path.of(repo.value().get("jsonfolder").asString()), version, build));
            }
            if (repo.value().contains("mailinglist")) {
                var mailcfg = repo.value().get("mailinglist").asObject();
                var senderName = mailcfg.get("name").asString();
                var senderMail = mailcfg.get("email").asString();
                var sender = EmailAddress.from(senderName, senderMail);
                updaters.add(new MailingListUpdater(mailcfg.get("smtp").asString(), EmailAddress.parse(mailcfg.get("recipient").asString()), sender, branches.size() > 1));
            }

            if (updaters.isEmpty()) {
                log.warning("No update consumers for updater bot configuration: " + repoName);
                continue;
            }

            var tagStorageBuilder = new StorageBuilder<Tag>(repoName + ".tags.txt")
                    .remoteRepository(databaseRepo, databaseRef, databaseName, databaseEmail, "Added tag for " + repoName);
            var branchStorageBuilder = new StorageBuilder<ResolvedBranch>(repoName + ".branches.txt")
                    .remoteRepository(databaseRepo, databaseRef, databaseName, databaseEmail, "Added branch hash for " + repoName);
            var bot = new JNotifyBot(configuration.repository(repoName), configuration.storageFolder(), branches, tagStorageBuilder, branchStorageBuilder, updaters);
            ret.add(bot);
        }

        return ret;
    }
}
