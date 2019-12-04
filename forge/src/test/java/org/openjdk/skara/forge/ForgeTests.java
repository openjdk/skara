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

import org.junit.jupiter.api.Test;
import org.openjdk.skara.host.Credential;
import org.openjdk.skara.json.JSONObject;

import java.net.URI;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ForgeTests {
    @Test
    void sortTest() {
        var allFactories = List.of(new ForgeFactory() {
                                       @Override
                                       public String name() {
                                           return "something";
                                       }

                                       @Override
                                       public Set<String> knownHosts() {
                                           return Set.of();
                                       }

                                       @Override
                                       public Forge create(URI uri, Credential credential, JSONObject configuration) {
                                           return null;
                                       }
                                   },
                                   new ForgeFactory() {
                                       @Override
                                       public String name() {
                                           return "other";
                                       }

                                       @Override
                                       public Set<String> knownHosts() {
                                           return Set.of();
                                       }

                                       @Override
                                       public Forge create(URI uri, Credential credential, JSONObject configuration) {
                                           return null;
                                       }
                                   });

        var sorted = allFactories.stream()
                                 .sorted(Comparator.comparing(f -> !f.name().contains("other")))
                                 .collect(Collectors.toList());

        assertEquals("something", allFactories.get(0).name());
        assertEquals("other", sorted.get(0).name());
    }
}
