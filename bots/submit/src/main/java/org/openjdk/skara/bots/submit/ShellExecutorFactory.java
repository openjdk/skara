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

import org.openjdk.skara.json.*;

import java.time.Duration;
import java.util.HashMap;
import java.util.stream.Collectors;

public class ShellExecutorFactory implements SubmitExecutorFactory {
    @Override
    public String name() {
        return "shell";
    }

    @Override
    public SubmitExecutor create(String name, Duration timeout, JSONObject config) {
        var cmd = config.get("cmd").stream()
                .map(JSONValue::asString)
                .collect(Collectors.toList());
        var checkName = config.get("name").asString();

        var env = new HashMap<String, String>();
        if (config.contains("env")) {
            for (var key : config.get("env").fields()) {
                env.put(key.name(), key.value().asString());
            }
        }

        return new ShellExecutor(checkName, cmd, timeout, env);
    }
}
