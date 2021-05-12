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
package org.openjdk.skara.bots.submit;

import org.openjdk.skara.bot.*;
import org.openjdk.skara.json.JSONValue;

import java.time.Duration;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

public class SubmitBotFactory implements BotFactory {
    static final String NAME = "submit";
    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<Bot> create(BotConfiguration configuration) {
        var ret = new ArrayList<Bot>();
        var specific = configuration.specific();

        var executorFactories = SubmitExecutorFactory.getSubmitExecutorFactories().stream()
                                                     .collect(Collectors.toMap(SubmitExecutorFactory::name,
                                                                               Function.identity()));
        var executorInstances = new HashMap<String, SubmitExecutor>();
        for (var executorDefinition : specific.get("executors").fields()) {
            var executorConfig = executorDefinition.value().asObject();
            var executorType = executorConfig.get("type").asString();
            var executorTimeout = Duration.parse(executorConfig.get("timeout").asString());
            if (!executorFactories.containsKey(executorType)) {
                throw new RuntimeException("Unknown executor type: " + executorType);
            }
            var executor = executorFactories.get(executorType).create(executorDefinition.name(),
                                                                      executorTimeout,
                                                                      executorConfig.get("config").asObject());
            executorInstances.put(executorDefinition.name(), executor);
        }

        for (var repo : specific.get("repositories").fields()) {
            var repoExecutors = repo.value().stream()
                                    .map(JSONValue::asString)
                                    .collect(Collectors.toSet());
            var repoInstances = executorInstances.entrySet().stream()
                                                 .filter(entry -> repoExecutors.contains(entry.getKey()))
                                                 .map(Map.Entry::getValue)
                                                 .collect(Collectors.toList());
            var bot = new SubmitBot(configuration.repository(repo.name()), repoInstances);
            ret.add(bot);
        }

        return ret;
    }
}
