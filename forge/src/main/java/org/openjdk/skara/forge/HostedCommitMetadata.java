/*
 * Copyright (c) 2020, Oracle and/or its affiliates. All rights reserved.
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

import org.openjdk.skara.vcs.*;

import java.net.URI;
import java.util.*;
import java.time.*;
import java.time.format.*;

public class HostedCommitMetadata {
    private final CommitMetadata metadata;
    private final URI url;

    public HostedCommitMetadata(CommitMetadata metadata, URI url) {
        this.metadata = metadata;
        this.url = url;
    }

    public Hash hash() {
        return metadata.hash();
    }

    public Author author() {
        return metadata.author();
    }

    public Author committer() {
        return metadata.committer();
    }

    public List<String> message() {
        return metadata.message();
    }

    public List<Hash> parents() {
        return metadata.parents();
    }

    public ZonedDateTime authored() {
        return metadata.authored();
    }

    public ZonedDateTime committed() {
        return metadata.committed();
    }

    public boolean isInitialCommit() {
        return metadata.isInitialCommit();
    }

    public boolean isMerge() {
        return metadata.isMerge();
    }

    public int numParents() {
        return metadata.numParents();
    }

    public URI url() {
        return url;
    }

    @Override
    public String toString() {
        return url.toString();
    }

    @Override
    public int hashCode() {
        return Objects.hash(metadata, url);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof HostedCommitMetadata)) {
            return false;
        }

        var other = (HostedCommitMetadata) o;
        return Objects.equals(metadata, other.metadata) &&
               Objects.equals(url, other.url);
    }
}
