/*
 * Copyright (c) 2019, 2024, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.bots.bridgekeeper;

import org.openjdk.skara.bot.*;
import org.openjdk.skara.forge.HostedRepository;
import org.openjdk.skara.json.JSONValue;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

public class BridgekeeperBotFactory implements BotFactory {
    static final String NAME = "bridgekeeper";
    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<Bot> create(BotConfiguration configuration) {
        var ret = new ArrayList<Bot>();
        var specific = configuration.specific();

        for (var repo : specific.get("mirrors").asArray()) {
            var bot = new PullRequestCloserBot(configuration.repository(repo.asString()), PullRequestCloserBot.Type.MIRROR);
            ret.add(bot);
        }
        for (var repo : specific.get("data").asArray()) {
            var bot = new PullRequestCloserBot(configuration.repository(repo.asString()), PullRequestCloserBot.Type.DATA);
            ret.add(bot);
        }
        var pruned = new HashMap<HostedRepository, Duration>();
        var ignoredUsers = specific.get("pruned").get("ignored").get("users").stream()
                .map(JSONValue::asString)
                .collect(Collectors.toSet());
        for (var repo : specific.get("pruned").get("repositories").fields()) {
            var maxAge = Duration.parse(repo.value().get("maxage").asString());
            pruned.put(configuration.repository(repo.name()), maxAge);
        }
        if (!pruned.isEmpty()) {
            var bot = new PullRequestPrunerBot(pruned, ignoredUsers);
            ret.add(bot);
        }
        return ret;
    }
}
