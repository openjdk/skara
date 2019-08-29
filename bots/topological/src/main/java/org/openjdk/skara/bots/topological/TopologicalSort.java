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
package org.openjdk.skara.bots.topological;

import org.openjdk.skara.vcs.Branch;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

class TopologicalSort {

    static List<Branch> tsort(List<Edge> edges) {
        List<Edge> eCopy = new ArrayList<>(edges);
        List<Branch> result = new ArrayList<>();
        while (!eCopy.isEmpty()) {
            Set<Branch> orphans = eCopy.stream()
                    .map(e -> e.from)
                    .filter(f -> eCopy.stream().map(e -> e.to).noneMatch(f::equals))
                    .collect(Collectors.toSet());
            if (orphans.isEmpty()) {
                throw new IllegalStateException("Detected a cycle! " + edges);
            }
            orphans.forEach(o -> {
                result.add(o);
                eCopy.removeIf(e -> o.equals(e.from));
            });
        }

        // add all leaves
        edges.stream()
            .map(e -> e.to)
            .filter(f -> edges.stream().map(e -> e.from).noneMatch(f::equals))
            .forEach(result::add);

        return result;
    }

}
