/*
 * Copyright (c) 2018, 2019 Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.bot;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import org.openjdk.skara.json.*;

import static org.junit.jupiter.api.Assertions.*;

class BotRunnerConfigurationTests {
    @Test
    void storageFolder() throws ConfigurationError {
        var input = JSON.object().put("storage", JSON.object().put("path", "/x"))
                        .put("xbot", JSON.object());
        var cfg = BotRunnerConfiguration.parse(input);
        var botCfg = cfg.perBotConfiguration("xbot");

        assertEquals(Path.of("/x/xbot"), botCfg.storageFolder());
    }

    @Test
    void parseHost() throws ConfigurationError {
        var input = JSON.object()
                        .put("xbot",
                             JSON.object().put("repository", "test/x/y"));
        var cfg = BotRunnerConfiguration.parse(input);
        var botCfg = cfg.perBotConfiguration("xbot");

        var error = assertThrows(RuntimeException.class, () -> botCfg.repository("test/x/y"));
        assertEquals("Repository entry test/x/y uses undefined host 'test'", error.getCause().getMessage());
    }

    @Test
    void parseRef() throws ConfigurationError {
        var input = JSON.object()
                        .put("xbot",
                             JSON.object().put("repository", "test/x/y:z"));
        var cfg = BotRunnerConfiguration.parse(input);
        var botCfg = cfg.perBotConfiguration("xbot");

        var error = assertThrows(RuntimeException.class, () -> botCfg.repositoryRef("test/x/y:z"));
        assertEquals("Repository entry test/x/y uses undefined host 'test'", error.getCause().getMessage());
    }
}
