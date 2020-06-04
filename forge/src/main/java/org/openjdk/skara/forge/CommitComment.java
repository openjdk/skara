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
package org.openjdk.skara.forge;

import org.openjdk.skara.host.HostUser;
import org.openjdk.skara.issuetracker.Comment;
import org.openjdk.skara.vcs.Hash;

import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.*;

public class CommitComment extends Comment {
    private final Hash commit;
    private final Path path;
    private final int position;
    private final int line;

    public CommitComment(Hash commit, Path path, int position, int line, String id, String body, HostUser author, ZonedDateTime createdAt, ZonedDateTime updatedAt) {
        super(id, body, author, createdAt, updatedAt);

        this.commit = commit;
        this.path = path;
        this.position = position;
        this.line = line;
    }

    /**
     * Returns the hash of the commit.
     */
    public Hash commit() {
        return commit;
    }

    /**
     * Returns the relative path of the file.
     */
    public Optional<Path> path() {
        return Optional.ofNullable(path);
    }

    /**
     * Returns the line index in the diff.
     */
    public Optional<Integer> position() {
        return position == -1 ? Optional.empty() : Optional.of(position);
    }

    /**
     * Returns the line number in the file.
     */
    public Optional<Integer> line() {
        return line == -1 ? Optional.empty() : Optional.of(line);
    }

    @Override
    public String toString() {
        return commit.hex() + ": " + body();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        if (!super.equals(o)) {
            return false;
        }
        var other = (CommitComment) o;
        return Objects.equals(commit, other.commit) &&
               Objects.equals(path, other.path) &&
               position == other.position &&
               line == other.line;
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), commit, path, position, line);
    }
}
