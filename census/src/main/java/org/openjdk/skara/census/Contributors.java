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

import org.openjdk.skara.xml.XML;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toMap;

class Contributors {
    static Map<String, Contributor> parse(Path p) throws IOException {
        var result = new ArrayList<Contributor>();

        var document = XML.parse(p);
        var contributors = XML.child(document, "contributors");
        for (var contributor : XML.children(contributors, "contributor")) {
            var username = XML.attribute(contributor, "username");
            var fullName = XML.attribute(contributor, "full-name");

            result.add(new Contributor(username, fullName));
        }

        return result.stream().collect(toMap(Contributor::username, identity()));
    }
}
