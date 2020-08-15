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

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.io.Writer;

public class TextualPatch extends Patch {
    private final List<Hunk> hunks;

    public TextualPatch(Path sourcePath, FileType sourceFileType, Hash sourceHash,
                 Path targetPath, FileType targetFileType, Hash targetHash,
                 Status status, List<Hunk> hunks) {
        super(sourcePath, sourceFileType, sourceHash, targetPath, targetFileType, targetHash, status);
        this.hunks = hunks;
    }

    public List<Hunk> hunks() {
        return hunks;
    }

    @Override
    public boolean isEmpty() {
        return hunks.isEmpty();
    }

    public WebrevStats stats() {
        int added = 0;
        int removed = 0;
        int modified = 0;

        for (var hunk : hunks()) {
            var stats = hunk.stats();
            added += stats.added();
            removed += stats.removed();
            modified += stats.modified();
        }

        return new WebrevStats(added, removed, modified);
    }
}
