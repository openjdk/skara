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
package org.openjdk.skara.census;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

public class Namespace {
    private final String name;
    private final Map<String, Contributor> mapping;
    private final Map<Contributor, String> reverse;

    private Namespace(String name, Map<String, Contributor> mapping, Map<Contributor, String> reverse) {
        this.name = name;
        this.mapping = mapping;
        this.reverse = reverse;
    }

    public String name() {
        return name;
    }

    public Contributor get(String id) {
        return mapping.get(id);
    }

    public String get(Contributor contributor) {
        return reverse.get(contributor);
    }

    static Namespace parse(Path p, Map<String, Contributor> contributors) throws IOException {
        var mapping = new HashMap<String, Contributor>();
        var reverse = new HashMap<Contributor, String>();

        var document = XML.parse(p);
        var namespace = XML.child(document, "namespace");
        var name = XML.attribute(namespace, "name");

        for (var user : XML.children(namespace, "user")) {
            var id = XML.attribute(user, "id");
            var to = XML.attribute(user, "census");

            if (!contributors.containsKey(to)) {
                throw new IllegalArgumentException("Unknown contributor " + to);
            }
            var contributor = contributors.get(to);
            mapping.put(id, contributor);
            reverse.put(contributor, id);
        }

        return new Namespace(name, mapping, reverse);
    }
}
