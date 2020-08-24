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
package org.openjdk.skara.cli;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class TestMLRules {
    @Test
    void collapseEquals() {
        assertEquals(Map.of("", List.of("v1")),
                     MLRules.stripDuplicatePrefixes(Map.of("k1", List.of("v1"))));
    }

    @Test
    void collapseSameList() {
        assertEquals(Map.of("", List.of("v1")),
                     MLRules.stripDuplicatePrefixes(Map.of("k1a", List.of("v1"),
                                                           "k1b", List.of("v1"))));
    }

    @Test
    void collapseDifferentList() {
        assertEquals(Map.of("k1a", List.of("v1"),
                            "k1b", List.of("v2")),
                     MLRules.stripDuplicatePrefixes(Map.of("k1a", List.of("v1"),
                                                           "k1b", List.of("v2"))));
    }

    @Test
    void collapseMultiple() {
        assertEquals(Map.of("", List.of("v1")),
                     MLRules.stripDuplicatePrefixes(Map.of("k1a", List.of("v1"),
                                                           "k1b", List.of("v1"),
                                                           "k2bb", List.of("v1"))));

    }

    @Test
    void collapseMultiple2() {
        assertEquals(Map.of("", List.of("v1")),
                     MLRules.stripDuplicatePrefixes(Map.of("k1a", List.of("v1"),
                                                           "k1b", List.of("v1"),
                                                           "k2bb", List.of("v1"),
                                                           "k4", List.of("v1"))));

    }

    @Test
    void collapseSingle() {
        assertEquals(Map.of("k1/a", List.of("v1"),
                            "k1/b", List.of("v2")),
                     MLRules.stripDuplicatePrefixes(Map.of("k1/a/a", List.of("v1"),
                                                           "k1/b/b", List.of("v2"))));

    }

    @Test
    void collapseSingle2() {
        assertEquals(Map.of("k/1", List.of("v1"),
                            "k/2a", List.of("v2")),
                     MLRules.stripDuplicatePrefixes(Map.of("k/1/aa", List.of("v1"),
                                                           "k/1/bb", List.of("v1"),
                                                           "k/2a", List.of("v2"))));

    }

}
