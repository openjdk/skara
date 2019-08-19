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
package org.openjdk.skara.vcs.git;

import org.openjdk.skara.vcs.*;
import org.openjdk.skara.vcs.tools.*;

import java.io.*;
import java.util.*;
import java.time.Instant;

class GitCommitIterator implements Iterator<Commit> {
    private final UnixStreamReader reader;
    private final String commitDelimiter;
    private String line;

    public GitCommitIterator(UnixStreamReader reader, String commitDelimiter) {
        this.reader = reader;
        this.commitDelimiter = commitDelimiter;
        try {
            line = reader.readLine();
        } catch (IOException e) {
            throw new RuntimeException(e);
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

            var metadata = GitCommitMetadata.read(reader);

            line = reader.readLine();   // read empty line before patches
            if (line == null || line.equals(commitDelimiter)) {
                // commit without patches
                var parentDiffs = new ArrayList<Diff>();
                for (var parentHash : metadata.parents()) {
                    parentDiffs.add(new Diff(parentHash, metadata.hash(), Collections.emptyList()));
                }
                return new Commit(metadata, parentDiffs);
            }

            if (!line.equals("")) {
                throw new IllegalStateException("Unexpected line: " + line);
            }

            var hash = metadata.hash();
            var parents = metadata.parents();

            List<Diff> parentDiffs = null;
            if (parents.size() == 1) {
                var patches = UnifiedDiffParser.parseGitRaw(reader, commitDelimiter);
                parentDiffs = List.of(new Diff(parents.get(0), hash, patches));
            } else {
                parentDiffs = new GitCombinedDiffParser(parents, hash, commitDelimiter).parse(reader);
            }
            line = reader.lastLine(); // update state for hasNext

            return new Commit(metadata, parentDiffs);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
