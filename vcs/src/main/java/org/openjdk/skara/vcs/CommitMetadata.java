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

import java.util.*;
import java.time.*;
import java.time.format.*;

public class CommitMetadata {
    private final Hash hash;
    private final List<Hash> parents;
    private final Author author;
    private final Author committer;
    private final ZonedDateTime date;
    private final List<String> message;

    public CommitMetadata(Hash hash, List<Hash> parents,
                          Author author, Author committer,
                          ZonedDateTime date, List<String> message) {
        this.hash = hash;
        this.parents = parents;
        this.author = author;
        this.committer = committer;
        this.date = date;
        this.message = message;
    }

    public Hash hash() {
        return hash;
    }

    public Author author() {
        return author;
    }

    public Author committer() {
        return committer;
    }

    public List<String> message() {
        return message;
    }

    public List<Hash> parents() {
        return parents;
    }

    public ZonedDateTime date() {
        return date;
    }

    public boolean isInitialCommit() {
        return numParents() == 1 && parents.get(0).equals(Hash.zero());
    }

    public boolean isMerge() {
        return parents().size() > 1;
    }

    public int numParents() {
        return parents().size();
    }

    @Override
    public String toString() {
        final var formatter = DateTimeFormatter.RFC_1123_DATE_TIME;
        final var displayDate = date.format(formatter);
        return String.format("%s  %-12s  %s  %s",
                             hash().toString(), author(), displayDate, message.get(0));
    }

    @Override
    public int hashCode() {
        return Objects.hash(hash, parents, author, committer, date, message);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof CommitMetadata)) {
            return false;
        }

        var other = (CommitMetadata) o;
        return Objects.equals(hash, other.hash) &&
               Objects.equals(parents, other.parents) &&
               Objects.equals(author, other.author) &&
               Objects.equals(committer, other.committer) &&
               Objects.equals(date, other.date) &&
               Objects.equals(message, other.message);
    }
}
