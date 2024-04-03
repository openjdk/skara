/*
 * Copyright (c) 2024, Oracle and/or its affiliates. All rights reserved.
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

import java.io.*;
import java.nio.file.*;
import java.util.Properties;

public class TestProperties {
    private static Properties PROPERTIES;
    private static Path FILE;

    static final String FILENAME = "test.properties";

    private TestProperties() {
    }

    private static Path findFileUpToRoot(String filename) {
        var dir = Path.of(".").toAbsolutePath();
        var f = dir.resolve(filename);
        while (!Files.exists(f)) {
            dir = dir.getParent();
            if (dir == null) {
                return null;
            }
            f = dir.resolve(filename);
        }
        return f;
    }

    private static Properties load(Path f) throws IOException {
        var properties = new Properties();
        try (InputStream in = Files.newInputStream(f)) {
            properties.load(in);
        }
        if (properties.getProperty("properties.include") != null) {
            var includedFile = Path.of(properties.getProperty("properties.include"));
            if (!includedFile.isAbsolute()) {
                throw new IOException("Cannot use relative paths for including properties: " + includedFile);
            }
            var included = load(includedFile);
            for (var key : included.keySet()) {
                // Allow included properties to be overridden
                if (properties.getProperty((String) key) == null) {
                    properties.setProperty((String) key, included.getProperty((String) key));
                }
            }
        }
        return properties;
    }

    public static TestProperties load() {
        // Only load properties once (no need to use locking, races are benign)
        if (PROPERTIES != null) {
            return new TestProperties();
        }

        FILE = findFileUpToRoot(FILENAME);
        if (FILE == null) {
            return new TestProperties();
        }
        try {
            PROPERTIES = load(FILE);
            return new TestProperties();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public boolean arePresent() {
        return PROPERTIES != null;
    }

    public String get(String key) {
        if (!arePresent()) {
            throw new IllegalStateException("Test properties have not been loaded");
        }
        if (!contains(key)) {
            throw new IllegalArgumentException("Could not find key '" + key + "' in: " + FILE);
        }
        return PROPERTIES.getProperty(key);
    }

    public boolean contains(String... keys) {
        if (!arePresent()) {
            return false;
        }

        for (var key : keys) {
            if (PROPERTIES.getProperty(key) == null) {
                return false;
            }
        }

        return true;
    }
}
