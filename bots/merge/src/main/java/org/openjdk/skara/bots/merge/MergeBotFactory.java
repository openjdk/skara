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
package org.openjdk.skara.bots.merge;

import org.openjdk.skara.bot.*;
import org.openjdk.skara.vcs.Branch;

import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.logging.Logger;

public class MergeBotFactory implements BotFactory {
    private final Logger log = Logger.getLogger("org.openjdk.skara.bots");;

    @Override
    public String name() {
        return "merge";
    }

    @Override
    public List<Bot> create(BotConfiguration configuration) {
        var storage = configuration.storageFolder();
        try {
            Files.createDirectories(storage);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        var specific = configuration.specific();

        var bots = new ArrayList<Bot>();
        for (var repo : specific.get("repositories").asArray()) {
            var targetRepo = configuration.repository(repo.get("target").asString());
            var forkRepo = configuration.repository(repo.get("fork").asString());

            var specs = new ArrayList<MergeBot.Spec>();
            for (var spec : repo.get("spec").asArray()) {
                var from = spec.get("from").asString().split(":");
                var fromRepo = configuration.repository(from[0]);
                var fromBranch = new Branch(from[1]);
                var toBranch = new Branch(spec.get("to").asString());
                specs.add(new MergeBot.Spec(fromRepo, fromBranch, toBranch));
            }

            bots.add(new MergeBot(storage, targetRepo, forkRepo, specs));
        }
        return bots;
    }
}
