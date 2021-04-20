/*
 * Copyright (c) 2020, 2021, Oracle and/or its affiliates. All rights reserved.
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
 package org.openjdk.skara.bots.notify.issue;

import org.openjdk.skara.bot.BotConfiguration;
import org.openjdk.skara.bots.notify.*;
import org.openjdk.skara.host.Credential;
import org.openjdk.skara.json.JSONObject;
import org.openjdk.skara.network.URIBuilder;

import java.net.URI;
import java.util.stream.Collectors;

public class IssueNotifierFactory implements NotifierFactory {
    @Override
    public String name() {
        return "issue";
    }

    @Override
    public Notifier create(BotConfiguration botConfiguration, JSONObject notifierConfiguration) {
        var issueProject = botConfiguration.issueProject(notifierConfiguration.get("project").asString());
        var builder = IssueNotifier.newBuilder()
                                   .issueProject(issueProject);

        if (notifierConfiguration.contains("reviews")) {
            if (notifierConfiguration.get("reviews").contains("icon")) {
                builder.reviewIcon(URI.create(notifierConfiguration.get("reviews").get("icon").asString()));
            }
        }
        if (notifierConfiguration.contains("commits")) {
            if (notifierConfiguration.get("commits").contains("icon")) {
                builder.commitIcon(URI.create(notifierConfiguration.get("commits").get("icon").asString()));
            }
        }

        if (notifierConfiguration.contains("reviewlink")) {
            builder.reviewLink(notifierConfiguration.get("reviewlink").asBoolean());
        }
        if (notifierConfiguration.contains("commitlink")) {
            builder.commitLink(notifierConfiguration.get("commitlink").asBoolean());
        }

        if (notifierConfiguration.contains("fixversions")) {
            builder.setFixVersion(true);
            builder.fixVersions(notifierConfiguration.get("fixversions").fields().stream()
                                                      .collect(Collectors.toMap(JSONObject.Field::name,
                                                                                f -> f.value().asString())));
        }
        if (notifierConfiguration.contains("buildname")) {
            builder.buildName(notifierConfiguration.get("buildname").asString());
        }

        if (notifierConfiguration.contains("vault")) {
            var vaultConfiguration = notifierConfiguration.get("vault").asObject();
            var credential = new Credential(vaultConfiguration.get("username").asString(), vaultConfiguration.get("password").asString());

            if (credential.username().startsWith("https://")) {
                var vaultUrl = URIBuilder.base(credential.username()).build();
                var jbsVault = new JbsVault(vaultUrl, credential.password(), issueProject.webUrl());
                builder.vault(jbsVault);
            } else {
                throw new RuntimeException("basic authentication not implemented yet");
            }
        }

        if (notifierConfiguration.contains("pronly")) {
            builder.prOnly(notifierConfiguration.get("pronly").asBoolean());
        }

        if (notifierConfiguration.contains("census")) {
            builder.censusRepository(botConfiguration.repository(notifierConfiguration.get("census").asString()));
            builder.censusRef(botConfiguration.repositoryRef(notifierConfiguration.get("census").asString()));
        }
        if (notifierConfiguration.contains("namespace")) {
            builder.namespace(notifierConfiguration.get("namespace").asString());
        }

        return builder.build();
    }
}
