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
package org.openjdk.skara.bots.notify.json;

import org.openjdk.skara.json.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.UUID;

public class JsonUpdateWriter implements AutoCloseable {

    private int sequence = 0;
    private final String baseName;
    private final Path path;
    private JSONArray current;

    private void flush() {
        var tempName = path.resolve(String.format("%s.%03d.temp", baseName, sequence));
        var finalName = path.resolve(String.format("%s.%03d.json", baseName, sequence));

        try {
            Files.write(tempName, current.toString().getBytes(StandardCharsets.UTF_8));
            Files.move(tempName, finalName);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        sequence++;
        current = JSON.array();
    }

    JsonUpdateWriter(Path path, String projectName) {
        this.path = path;

        var uuid = UUID.randomUUID();
        baseName = "jbs." + projectName.replace("/", ".") + "." + uuid.toString().replace("-", "");
        current = JSON.array();
    }

    public void write(JSONObject obj) {
        current.add(obj);
        if (current.size() > 100) {
            flush();
        }
    }

    @Override
    public void close() {
        if (current.size() > 0) {
            flush();
        }
    }
}
