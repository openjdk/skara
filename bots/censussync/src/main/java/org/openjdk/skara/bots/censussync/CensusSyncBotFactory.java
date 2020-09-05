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
package org.openjdk.skara.bots.censussync;

import org.openjdk.skara.bot.*;
import org.openjdk.skara.vcs.*;
import org.openjdk.skara.vcs.openjdk.convert.Mark;

import java.util.*;
import java.net.URI;
import java.nio.file.Path;
import java.util.logging.Logger;

public class CensusSyncBotFactory implements BotFactory {
    private static final Logger log = Logger.getLogger("org.openjdk.skara.bots");

    @Override
    public String name() {
        return "censussync";
    }

    @Override
    public List<Bot> create(BotConfiguration configuration) {
        var bots = new ArrayList<Bot>();
        var specific = configuration.specific();
        for (var sync : specific.get("sync").asArray()) {
            var from = configuration.repository(sync.get("from").asString());
            var to = configuration.repository(sync.get("to").asString());
            var version = sync.get("version").asInt();
            bots.add(new CensusSyncBot(from, to, version));
        }
        return bots;
    }
}
