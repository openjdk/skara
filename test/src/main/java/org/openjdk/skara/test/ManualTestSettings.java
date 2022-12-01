/*
 * Copyright (c) 2021, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.Properties;

/**
 * This class provides settings for manual tests which the user provides
 * through the manual-test-settings.properties file in the root of the project.
 */
public class ManualTestSettings {

    public static final String MANUAL_TEST_SETTINGS_FILE = "manual-test-settings.properties";

    public static Properties loadManualTestSettings() throws IOException {
        var dir = Paths.get(".").toAbsolutePath();
        Path file = dir.resolve(MANUAL_TEST_SETTINGS_FILE);
        while (!Files.exists(file)) {
            dir = dir.getParent();
            if (!Files.isDirectory(dir)) {
                throw new RuntimeException("Could not find " + MANUAL_TEST_SETTINGS_FILE);
            }
            file = dir.resolve(MANUAL_TEST_SETTINGS_FILE);
        }
        var properties = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            properties.load(in);
        }
        return properties;
    }
}
