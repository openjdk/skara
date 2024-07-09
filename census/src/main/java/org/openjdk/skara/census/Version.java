/*
 * Copyright (c) 2018, 2024, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.census;

import org.openjdk.skara.xml.XML;

import java.time.Instant;
import java.nio.file.Path;
import java.io.IOException;
import java.util.Objects;

public class Version {
    private final int format;
    private final Instant timestamp;

    Version(int format, Instant timestamp) {
        this.format = format;
        this.timestamp = timestamp;
    }

    public Instant timestamp() {
        return timestamp;
    }

    public int format() {
        return format;
    }

    static Version parse(Path p) throws IOException {
        var document = XML.parse(p);
        var version = XML.child(document, "version");
        var format = Integer.parseInt(XML.attribute(version, "format"));
        var timestamp = Instant.parse(XML.attribute(version, "timestamp"));

        return new Version(format, timestamp);
    }

    @Override
    public int hashCode() {
        return Objects.hash(format, timestamp);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Version other)) {
            return false;
        }

        return Objects.equals(format, other.format()) &&
               Objects.equals(timestamp, other.timestamp());
    }

    @Override
    public String toString() {
        return format + " at " + timestamp.toString();
    }
}
