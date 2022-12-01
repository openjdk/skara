/*
 * Copyright (c) 2018, 2020, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.vcs.hg;

import org.openjdk.skara.vcs.*;
import org.openjdk.skara.vcs.tools.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.time.*;
import java.time.format.DateTimeFormatter;

class HgCommitIterator implements Iterator<Commit> {
    public static final String commitDelimiter = "#@!_-=&";
    private final UnixStreamReader reader;
    private String line;

    public HgCommitIterator(UnixStreamReader reader) {
        this.reader = reader;
        try {
            line = reader.readLine();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public boolean hasNext() {
        return line != null;
    }

    public Commit next() {
        if (line == null) {
            return null;
        }

        try {
            if (!line.equals(commitDelimiter)) {
                throw new IllegalStateException("Unexpected line: " + line);
            }

            var metadata = HgCommitMetadata.read(reader);
            line = reader.lastLine(); // update state for hasNext

            var hash = metadata.hash();
            var parents = metadata.parents();

            List<Diff> parentDiffs = null;
            if (metadata.parents().size() == 1) {
                var patches = GitRawDiffParser.parse(reader, commitDelimiter);
                parentDiffs = List.of(new Diff(parents.get(0), hash, patches));
            } else {
                var p0 = GitRawDiffParser.parse(reader, commitDelimiter);
                var p1 = GitRawDiffParser.parse(reader, commitDelimiter);
                parentDiffs = List.of(new Diff(parents.get(0), hash, p0),
                                      new Diff(parents.get(1), hash, p1));
            }
            line = reader.lastLine(); // update state for hasNext

            return new Commit(metadata, parentDiffs);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

