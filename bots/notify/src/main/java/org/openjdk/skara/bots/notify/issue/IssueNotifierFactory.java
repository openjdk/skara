/*
 * Copyright (c) 2020, 2024, Oracle and/or its affiliates. All rights reserved.
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.regex.Pattern;
import org.openjdk.skara.bot.BotConfiguration;
import org.openjdk.skara.bots.notify.*;
import org.openjdk.skara.host.Credential;
import org.openjdk.skara.json.JSONObject;
import org.openjdk.skara.json.JSONValue;
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
            var fixVersions = new LinkedHashMap<Pattern, String>();
            notifierConfiguration.get("fixversions").fields()
                    .forEach(f -> fixVersions.put(Pattern.compile(f.name()), f.value().asString()));
            builder.fixVersions(fixVersions);
        }
        if (notifierConfiguration.contains("altfixversions")) {
            var altFixVersions = new LinkedHashMap<Pattern, List<Pattern>>();
            notifierConfiguration.get("altfixversions").fields()
                    .forEach(f -> altFixVersions.put(Pattern.compile(f.name()), f.value().asArray().stream()
                            .map(JSONValue::asString)
                            .map(Pattern::compile)
                            .toList()));
            builder.altFixVersions(altFixVersions);
        }
        if (notifierConfiguration.contains("buildname")) {
            builder.buildName(notifierConfiguration.get("buildname").asString());
        }

        if (notifierConfiguration.contains("pronly")) {
            builder.prOnly(notifierConfiguration.get("pronly").asBoolean());
        }

        if (notifierConfiguration.contains("resolve")) {
            builder.resolve(notifierConfiguration.get("resolve").asBoolean());
            if (!builder.resolve() && !builder.prOnly()) {
                throw new RuntimeException("Cannot disable resolve when pronly is false");
            }
        }

        if (notifierConfiguration.contains("repoonly")) {
            builder.repoOnly(notifierConfiguration.get("repoonly").asBoolean());
        }

        if (notifierConfiguration.contains("census")) {
            builder.censusRepository(botConfiguration.repository(notifierConfiguration.get("census").asString()));
            builder.censusRef(botConfiguration.repositoryRef(notifierConfiguration.get("census").asString()));
        }
        if (notifierConfiguration.contains("namespace")) {
            builder.namespace(notifierConfiguration.get("namespace").asString());
        }

        if (notifierConfiguration.contains("headversion")) {
            builder.useHeadVersion(notifierConfiguration.get("headversion").asBoolean());
        }

        if (notifierConfiguration.contains("originalrepository")) {
            builder.originalRepository(botConfiguration.repository(notifierConfiguration.get("originalrepository").asString()));
        }

        if (notifierConfiguration.contains("tag")) {
            var tag = notifierConfiguration.get("tag");
            if (tag.contains("ignoreopt")) {
                builder.tagIgnoreOpt(tag.get("ignoreopt").stream()
                        .map(JSONValue::asString)
                        .collect(Collectors.toSet()));
            }
            if (tag.contains("matchprefix")) {
                builder.tagMatchPrefix(tag.get("matchprefix").asBoolean());
            }
        }

        if (notifierConfiguration.contains("defaultsecurity")) {
            var defaultSecurity = notifierConfiguration.get("defaultsecurity").fields().stream()
                    .map(e -> new IssueNotifier.BranchSecurity(Pattern.compile(e.name()), e.value().asString()))
                    .toList();
            builder.defaultSecurity(defaultSecurity);
        }

        if (notifierConfiguration.contains("avoidforwardports")) {
            builder.avoidForwardports(notifierConfiguration.get("avoidforwardports").asBoolean());
        }

        if (notifierConfiguration.contains("multifixversions")) {
            builder.multiFixVersions(notifierConfiguration.get("multifixversions").asBoolean());
        }

        return builder.build();
    }
}
