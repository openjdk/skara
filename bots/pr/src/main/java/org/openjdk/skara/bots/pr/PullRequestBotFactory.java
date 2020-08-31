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
package org.openjdk.skara.bots.pr;

import org.openjdk.skara.bot.*;
import org.openjdk.skara.forge.LabelConfiguration;
import org.openjdk.skara.json.*;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class PullRequestBotFactory implements BotFactory {
    @Override
    public String name() {
        return "pr";
    }

    @Override
    public List<Bot> create(BotConfiguration configuration) {
        var ret = new ArrayList<Bot>();
        var specific = configuration.specific();

        var external = new HashMap<String, String>();
        if (specific.contains("external")) {
            for (var command : specific.get("external").fields()) {
                external.put(command.name(), command.value().asString());
            }
        }

        var blockers = new HashMap<String, String>();
        if (specific.contains("blockers")) {
            for (var blocker : specific.get("blockers").fields()) {
                blockers.put(blocker.name(), blocker.value().asString());
            }
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
            labelConfigurations.put(labelGroup.name(),
                                    LabelConfiguration.fromJSON(labelGroup.value()));
        }

        for (var repo : specific.get("repositories").fields()) {
            var censusRepo = configuration.repository(repo.value().get("census").asString());
            var censusRef = configuration.repositoryRef(repo.value().get("census").asString());

            var botBuilder = PullRequestBot.newBuilder()
                                           .repo(configuration.repository(repo.name()))
                                           .censusRepo(censusRepo)
                                           .censusRef(censusRef)
                                           .blockingCheckLabels(blockers)
                                           .readyLabels(readyLabels)
                                           .readyComments(readyComments)
                                           .externalCommands(external)
                                           .seedStorage(configuration.storageFolder().resolve("seeds"));

            if (repo.value().contains("labels")) {
                var labelGroup = repo.value().get("labels").asString();
                if (!labelConfigurations.containsKey(labelGroup)) {
                    throw new RuntimeException("Unknown label group: " + labelGroup);
                }
                botBuilder.labelConfiguration(labelConfigurations.get(labelGroup));
            }
            if (repo.value().contains("issues")) {
                botBuilder.issueProject(configuration.issueProject(repo.value().get("issues").asString()));
            }
            if (repo.value().contains("ignorestale")) {
                botBuilder.ignoreStaleReviews(repo.value().get("ignorestale").asBoolean());
            }
            if (repo.value().contains("issuetypes")) {
                var types = repo.value().get("issuetypes").stream()
                                                          .map(JSONValue::asString)
                                                          .collect(Collectors.toSet());
                botBuilder.allowedIssueTypes(types);
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

            ret.add(botBuilder.build());
        }

        return ret;
    }
}
