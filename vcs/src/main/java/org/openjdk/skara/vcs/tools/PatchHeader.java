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
package org.openjdk.skara.vcs.tools;

import org.openjdk.skara.vcs.*;

import java.nio.file.Path;
import java.util.Objects;

public class PatchHeader {
    private Path sourcePath;
    private FileType sourceFileType;
    private Hash sourceHash;

    private Path targetPath;
    private FileType targetFileType;
    private Hash targetHash;

    private Status status;

    public PatchHeader(Path sourcePath, FileType sourceFileType, Hash sourceHash,
                       Path targetPath, FileType targetFileType, Hash targetHash,
                       Status status) {
        this.sourcePath = sourcePath;
        this.sourceFileType = sourceFileType;
        this.sourceHash = sourceHash;
        this.targetPath = targetPath;
        this.targetFileType = targetFileType;
        this.targetHash = targetHash;
        this.status = status;
    }

    public Path sourcePath() {
        return sourcePath;
    }

    public FileType sourceFileType() {
        return sourceFileType;
    }

    public Hash sourceHash() {
        return sourceHash;
    }

    public Path targetPath() {
        return targetPath;
    }

    public FileType targetFileType() {
        return targetFileType;
    }

    public Hash targetHash() {
        return targetHash;
    }

    public Status status() {
        return status;
    }

    public static PatchHeader fromRawLine(String line) {
        if (line == null || line.isEmpty() || line.charAt(0) != ':') {
            throw new IllegalArgumentException("Raw line does not start with colon: " + line);
        }
        line = line.substring(1); // skip the first ':' char

        var words = line.split("\\s");
        var sourceType = FileType.fromOctal(words[0]);
        var targetType = FileType.fromOctal(words[1]);

        var sourceHash = new Hash(words[2]);
        var targetHash = new Hash(words[3]);

        var status = Status.from(words[4]);

        Path sourcePath = null;
        Path targetPath = null;
        if (status.isModified()) {
            sourcePath = Path.of(words[5]);
            targetPath = sourcePath;
        } else if (status.isAdded()) {
            targetPath = Path.of(words[5]);
        } else if (status.isDeleted()) {
            sourcePath = Path.of(words[5]);
        } else {
            // either copied or renamed
            sourcePath = Path.of(words[5]);
            targetPath = Path.of(words[6]);
        }

        return new PatchHeader(sourcePath, sourceType, sourceHash, targetPath, targetType, targetHash, status);
    }

    public String toRawLine() {
        var sb = new StringBuilder();
        sb.append(":");
        if (sourceFileType == null) {
            sb.append("000000");
        } else {
            sb.append(sourceFileType.toOctal());
        }
        sb.append(" ");
        if (targetFileType == null) {
            sb.append("000000");
        } else {
            sb.append(targetFileType.toOctal());
        }
        sb.append(" ");
        sb.append(status.toString());
        sb.append(" ");
        if (sourcePath != null) {
            sb.append(sourcePath.toString());
        }
        if (targetPath != null) {
            if (sourcePath != null) {
                sb.append(" ");
            }
            sb.append(targetPath.toString());
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return toRawLine();
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof PatchHeader)) {
            return false;
        }

        var other = (PatchHeader) o;
        return Objects.equals(sourcePath, other.sourcePath()) &&
               Objects.equals(sourceFileType, other.sourceFileType()) &&
               Objects.equals(sourceHash, other.sourceHash()) &&
               Objects.equals(targetPath, other.targetPath()) &&
               Objects.equals(targetFileType, other.targetFileType()) &&
               Objects.equals(targetHash, other.targetHash()) &&
               Objects.equals(status, other.status());
    }

    @Override
    public int hashCode() {
        return Objects.hash(sourcePath, sourceFileType, targetPath, targetFileType, status);
    }
}
