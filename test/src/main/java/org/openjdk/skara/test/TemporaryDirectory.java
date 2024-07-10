/*
 * Copyright (c) 2019, 2024, Oracle and/or its affiliates. All rights reserved.
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
import java.util.*;

public class TemporaryDirectory implements AutoCloseable {
    private final Path p;
    private final boolean shouldRemove;

    public TemporaryDirectory() {
        this(true);
    }

    public TemporaryDirectory(boolean shouldRemove) {
        try {
            p = Files.createTempDirectory("RepositoryTests").toRealPath();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        this.shouldRemove = shouldRemove;
    }

    public Path path() {
        return p;
    }

    @Override
    public void close() {
        if (shouldRemove) {
            try (var paths = Files.walk(p)) {
                paths.map(Path::toFile)
                     .sorted(Comparator.reverseOrder())
                     .forEach(File::delete);
            } catch (IOException io) {
                throw new RuntimeException(io);
            }
        } else {
            System.out.println("TemporaryDirectory: " + p.toString());
        }
    }

    @Override
    public String toString() {
        return p.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        TemporaryDirectory that = (TemporaryDirectory) o;
        return Objects.equals(p, that.p);
    }

    @Override
    public int hashCode() {
        return Objects.hash(p);
    }
}
