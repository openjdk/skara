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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;
import org.openjdk.skara.vcs.Branch;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TopologicalSortTest {

    private static Edge edge(String from, String to) {
        return new Edge(new Branch(from), new Branch(to));
    }

    private static List<Branch> brancheList(String... names) {
        return Arrays.stream(names).map(Branch::new).collect(Collectors.toList());
    }

    @Test
    void testEmpty() {
        var branches = TopologicalSort.sort(List.of());
        assertEquals(brancheList(), branches);
    }

    @Test
    void testTrivial() {
        var branches = TopologicalSort.sort(List.of(edge("A", "B")));
        assertEquals(brancheList("A", "B"), branches);
    }

    @Test()
    void testCycleTrivial() {
        assertThrows(IllegalStateException.class, () -> TopologicalSort.sort(List.of(edge("A", "A"))));
    }

    @Test()
    void testCycle() {
        assertThrows(IllegalStateException.class, () ->
                TopologicalSort.sort(List.of(edge("B", "C"), edge("A", "B"), edge("C", "A"))));
    }

    @ParameterizedTest
    @ArgumentsSource(EdgeProvider.class)
    void testSort(List<Edge> edges) {
        var branches = TopologicalSort.sort(edges);
        assertEquals(brancheList("A", "B", "C", "D", "E"), branches);
    }

    private static class EdgeProvider implements ArgumentsProvider {
        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext context) throws Exception {
            List<Edge> edges = List.of(edge("A", "B"), edge("B", "C"), edge("C", "D"), edge("B", "D"), edge("D", "E"));
            List<List<Edge>> permutations = new ArrayList<>();
            permutations(edges, List.of(), permutations);
            return permutations.stream().map(Arguments::arguments);
        }

        static void permutations(List<Edge> source, List<Edge> perm, List<List<Edge>> result) {
            if (source.size() == perm.size()) {
                result.add(perm);
                return;
            }
            for (var edge : source) {
                if (!perm.contains(edge)) {
                    List<Edge> newPerm = new ArrayList<>(perm);
                    newPerm.add(edge);
                    permutations(source, newPerm, result);
                }
            }
        }
    }
}
