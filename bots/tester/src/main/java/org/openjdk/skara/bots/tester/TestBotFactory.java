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
package org.openjdk.skara.bots.tester;

import org.openjdk.skara.bot.*;
import org.openjdk.skara.json.*;

import org.openjdk.skara.ci.ContinuousIntegration;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import java.net.URI;

public class TestBotFactory implements BotFactory {
    @Override
    public String name() {
        return "test";
    }

    @Override
    public List<Bot> create(BotConfiguration configuration) {
        var storage = configuration.storageFolder();
        try {
            Files.createDirectories(storage);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        var ret = new ArrayList<Bot>();
        var specific = configuration.specific();

        var approvers = specific.get("approvers").asString();
        var availableJobs = specific.get("availableJobs").stream().map(JSONValue::asString).collect(Collectors.toList());
        var defaultJobs = specific.get("defaultJobs").stream().map(JSONValue::asString).collect(Collectors.toList());
        var name = specific.get("name").asString();
        var ci = configuration.continuousIntegration(specific.get("ci").asString());
        for (var repo : specific.get("repositories").asArray()) {
            var hostedRepo = configuration.repository(repo.asString());
            ret.add(new TestBot(ci, approvers, availableJobs, defaultJobs, name, storage, hostedRepo));
        }

        return ret;
    }
}
