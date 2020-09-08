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

package org.openjdk.skara.bots.checkout;

import org.openjdk.skara.forge.HostedRepository;
import org.openjdk.skara.vcs.*;
import org.openjdk.skara.vcs.openjdk.convert.Mark;
import org.openjdk.skara.storage.StorageBuilder;

import java.net.URI;
import java.util.*;
import java.util.stream.Collectors;

class MarkStorage {
    private static Mark deserializeMark(String s) {
        var parts = s.split(" ");
        if (!(parts.length == 3 || parts.length == 4)) {
            throw new IllegalArgumentException("Unexpected string:" + s);
        }

        var key = Integer.parseInt(parts[0]);
        var hg = new Hash(parts[1]);
        var git = new Hash(parts[2]);

        return parts.length == 3 ? new Mark(key, hg, git) : new Mark(key, hg, git, new Hash(parts[3]));
    }

    private static String serialize(Collection<Mark> added, Set<Mark> existing) {
        var marks = new ArrayList<Mark>();
        var handled = new HashSet<Integer>();
        for (var mark : added) {
            marks.add(mark);
            handled.add(mark.key());
        }
        for (var mark : existing) {
            if (!handled.contains(mark.key())) {
                marks.add(mark);
            }
        }
        Collections.sort(marks);
        var sb = new StringBuilder();
        for (var mark : marks) {
            sb.append(Integer.toString(mark.key()));
            sb.append(" ");
            sb.append(mark.hg().hex());
            sb.append(" ");
            sb.append(mark.git().hex());
            if (mark.tag().isPresent()) {
                sb.append(" ");
                sb.append(mark.tag().get().hex());
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private static Set<Mark> deserialize(String current) {
        var res = current.lines()
                         .map(MarkStorage::deserializeMark)
                         .collect(Collectors.toSet());
        return res;
    }

    static StorageBuilder<Mark> create(HostedRepository repo, Author user, String name) {
        return new StorageBuilder<Mark>(name + "/marks.txt")
            .remoteRepository(repo, Branch.defaultFor(VCS.GIT).name(), user.name(), user.email(), "Updated marks for " + name)
            .serializer(MarkStorage::serialize)
            .deserializer(MarkStorage::deserialize);
    }
}
