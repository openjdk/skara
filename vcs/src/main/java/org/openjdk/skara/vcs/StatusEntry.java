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
package org.openjdk.skara.vcs;

import org.openjdk.skara.vcs.tools.PatchHeader;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

public class StatusEntry {
    public static final class Info {
        private final Path path;
        private final FileType type;
        private final Hash hash;

        private Info(Path path, FileType type, Hash hash) {
            this.path = path;
            this.type = type;
            this.hash = hash;
        }

        private Info(Patch.Info info) {
            this.path = info.path().orElse(null);
            this.type = info.type().orElse(null);
            this.hash = info.hash();
        }

        public Optional<Path> path() {
            return Optional.ofNullable(path);
        }

        public Optional<FileType> type() {
            return Optional.ofNullable(type);
        }

        public Hash hash() {
            return hash;
        }
    }

    private final Info source;
    private final Info target;

    private Status status;

    public StatusEntry(Path sourcePath, FileType sourceFileType, Hash sourceHash,
                       Path targetPath, FileType targetFileType, Hash targetHash,
                       Status status) {
        this.source = new Info(sourcePath, sourceFileType, sourceHash);
        this.target = new Info(targetPath, targetFileType, targetHash);
        this.status = status;
    }

    public StatusEntry(Patch patch) {
        this.source = new Info(patch.source());
        this.target = new Info(patch.target());
        this.status = patch.status();
    }

    public Info source() {
        return source;
    }

    public Info target() {
        return target;
    }

    public Status status() {
        return status;
    }

    public static StatusEntry fromRawLine(String line) {
        var h = PatchHeader.fromRawLine(line);
        return new StatusEntry(h.sourcePath(), h.sourceFileType(), h.sourceHash(),
                               h.targetPath(), h.targetFileType(), h.targetHash(),
                               h.status());
    }
}
