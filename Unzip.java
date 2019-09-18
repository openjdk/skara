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

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.zip.ZipInputStream;

public class Unzip {
    private static void unzip(Path zipFile, Path dest) throws IOException {
        var stream = new ZipInputStream(Files.newInputStream(zipFile));
        for (var entry = stream.getNextEntry(); entry != null; entry = stream.getNextEntry()) {
            var path = dest.resolve(entry.getName());
            if (entry.isDirectory()) {
                Files.createDirectories(path);
            } else {
                if (Files.exists(path)) {
                    Files.delete(path);
                }
                Files.copy(stream, path);
            }
        }
    }

    public static void main(String[] args) throws IOException {
        unzip(Path.of(args[0]), Path.of(args[1]));
    }
}
