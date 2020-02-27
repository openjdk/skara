/*
 * Copyright (c) 2018, 2019, Oracle and/or its affiliates. All rights reserved.
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

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Optional;

public abstract class Patch {
    public static final class Info {
        private final Path path;
        private final FileType type;
        private final Hash hash;

        private Info(Path path, FileType type, Hash hash) {
            this.path = path;
            this.type = type;
            this.hash = hash;
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

    private final Status status;

    public Patch(Path sourcePath, FileType sourceFileType, Hash sourceHash,
                 Path targetPath, FileType targetFileType, Hash targetHash,
                 Status status) {
        this.source = new Info(sourcePath, sourceFileType, sourceHash);
        this.target = new Info(targetPath, targetFileType, targetHash);
        this.status = status;
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

    public abstract boolean isEmpty();

    public boolean isBinary() {
        return this instanceof BinaryPatch;
    }

    public boolean isTextual() {
        return this instanceof TextualPatch;
    }

    public TextualPatch asTextualPatch() {
        if (isTextual()) {
            return (TextualPatch) this;
        }
        throw new IllegalStateException("Cannot convert binary patch to textual");
    }

    public BinaryPatch asBinaryPatch() {
        if (isBinary()) {
            return (BinaryPatch) this;
        }
        throw new IllegalStateException("Cannot convert textual patch to binary");
    }

    public void write(BufferedWriter w) throws IOException {
        // header
        var sourcePath = pathWithUnixSeps(source.path().isPresent() ?
            source.path().get() : target.path().get());
        var targetPath = pathWithUnixSeps(target.path().isPresent() ?
            target.path().get() : source.path().get());

        w.append("diff --git ");
        w.append("a/" + sourcePath);
        w.append(" ");
        w.append("b/" + targetPath);
        w.write("\n");

        // extended headers
        if (status.isModified()) {
            if (!source.type().get().equals(target.type().get())) {
                w.append("old mode ");
                w.append(source.type().get().toOctal());
                w.write("\n");

                w.append("new mode ");
                w.append(target.type().get().toOctal());
                w.write("\n");
            }
            w.append("index ");
            w.append(source().hash().hex());
            w.append("..");
            w.append(target().hash().hex());
            w.append(" ");
            w.append(target.type().get().toOctal());
            w.write("\n");
        } else if (status.isAdded()) {
            w.append("new file mode ");
            w.append(target.type().get().toOctal());
            w.write("\n");

            w.append("index ");
            w.append(Hash.zero().hex());
            w.append("..");
            w.append(target.hash().hex());
            w.write("\n");
        } else if (status.isDeleted()) {
            w.append("deleted file mode ");
            w.append(source.type().get().toOctal());
            w.write("\n");

            w.append("index ");
            w.append(source.hash().hex());
            w.append("..");
            w.append(Hash.zero().hex());
            w.write("\n");
        } else if (status.isCopied()) {
            w.append("similarity index ");
            w.append(Integer.toString(status.score()));
            w.append("%");
            w.write("\n");

            w.append("copy from ");
            w.append(source.path().get().toString());
            w.write("\n");
            w.append("copy to ");
            w.append(target.path().get().toString());
            w.write("\n");

            w.append("index ");
            w.append(source().hash().hex());
            w.append("..");
            w.append(target().hash().hex());
            w.append(" ");
            w.append(target.type().get().toOctal());
            w.write("\n");
        } else if (status.isRenamed()) {
            w.append("similarity index ");
            w.append(Integer.toString(status.score()));
            w.append("%");
            w.write("\n");

            w.append("rename from ");
            w.append(source.path().get().toString());
            w.write("\n");
            w.append("rename to ");
            w.append(target.path().get().toString());
            w.write("\n");

            w.append("index ");
            w.append(source().hash().hex());
            w.append("..");
            w.append(target().hash().hex());
            w.append(" ");
            w.append(target.type().get().toOctal());
            w.write("\n");
        }

        w.append("--- ");
        w.append(source.path().isPresent() ? "a/" + sourcePath : "/dev/null");
        w.append("\n");
        w.append("+++ ");
        w.append(target.path().isPresent() ? "b/" + targetPath : "/dev/null");
        w.write("\n");

        if (isBinary()) {
            w.append("GIT binary patch");
            w.write("\n");
            for (var hunk : asBinaryPatch().hunks()) {
                hunk.write(w);
            }
        } else {
            for (var hunk : asTextualPatch().hunks()) {
                hunk.write(w);
            }
        }
    }

    public void toFile(Path p) throws IOException {
        try (var w = Files.newBufferedWriter(p)) {
            write(w);
        }
    }

    public static String pathWithUnixSeps(Path p) {
        return p.toString().replace('\\', '/');
    }
}
