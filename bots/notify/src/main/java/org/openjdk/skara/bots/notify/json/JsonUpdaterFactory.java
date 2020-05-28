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
package org.openjdk.skara.bots.notify.json;

import org.openjdk.skara.bot.BotConfiguration;
import org.openjdk.skara.bots.notify.*;
import org.openjdk.skara.json.JSONObject;

import java.nio.file.Path;

public class JsonUpdaterFactory implements NotifierFactory {
    @Override
    public String name() {
        return "json";
    }

    @Override
    public Notifier create(BotConfiguration botConfiguration, JSONObject notifierConfiguration) {
        var folder = notifierConfiguration.get("folder").asString();
        var build = notifierConfiguration.get("build").asString();
        var version = notifierConfiguration.get("version").asString();

        return new JsonUpdater(Path.of(folder), version, build);
    }
}
