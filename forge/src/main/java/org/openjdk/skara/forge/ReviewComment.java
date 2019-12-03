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

import java.time.ZonedDateTime;
import java.util.*;

public class ReviewComment extends Comment {
    private final ReviewComment parent;
    private final String threadId;
    private final Hash hash;
    private final String path;
    private final int line;

    public ReviewComment(ReviewComment parent, String threadId, Hash hash, String path, int line, String id, String body, HostUser author, ZonedDateTime createdAt, ZonedDateTime updatedAt) {
        super(id, body, author, createdAt, updatedAt);

        this.parent = parent;
        this.threadId = threadId;
        this.hash = hash;
        this.path = path;
        this.line = line;
    }

    public Optional<ReviewComment> parent() {
        return Optional.ofNullable(parent);
    }

    public Hash hash() {
        return hash;
    }

    public String path() {
        return path;
    }

    public int line() {
        return line;
    }

    public String threadId() {
        return threadId;
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
        ReviewComment that = (ReviewComment) o;
        return line == that.line &&
                Objects.equals(parent, that.parent) &&
                threadId.equals(that.threadId) &&
                hash.equals(that.hash) &&
                path.equals(that.path);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), parent, threadId, hash, path, line);
    }
}
