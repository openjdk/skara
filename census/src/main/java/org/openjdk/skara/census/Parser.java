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

import java.util.*;

import org.openjdk.skara.xml.XML;
import org.w3c.dom.*;

class Parser {
    public static List<Member> members(List<Element> elements, Map<String, Contributor> contributors) {
        var members = new ArrayList<Member>();

        for (var element : elements) {
            var username = XML.attribute(element, "username");
            if (!contributors.containsKey(username)) {
                contributors.put(username, new Contributor(username));
            }
            var contributor = contributors.get(username);

            var since = Integer.parseInt(XML.attribute(element, "since"));

            if (XML.hasAttribute(element, "until")) {
                var until = Integer.parseInt(XML.attribute(element, "until"));
                members.add(new Member(contributor, since, until));
            } else {
                members.add(new Member(contributor, since));
            }
        }

        return members;
    }
}
