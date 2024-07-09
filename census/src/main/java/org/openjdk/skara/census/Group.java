/*
 * Copyright (c) 2018, 2024, Oracle and/or its affiliates. All rights reserved.
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
import java.nio.file.*;
import java.util.*;

import org.openjdk.skara.xml.XML;
import org.w3c.dom.*;

public class Group {
    private final String name;
    private final String fullName;
    private final Contributor lead;
    private final Map<String, Contributor> members = new HashMap<>();

    Group(String name, String fullName, Contributor lead, List<Contributor> members) {
        this.name = name;
        this.fullName = fullName;
        this.lead = lead;

        for (var member : members) {
            this.members.put(member.username(), member);
        }
        this.members.put(lead.username(), lead);
    }

    public Set<Contributor> members() {
        var result = new HashSet<Contributor>();
        for (var username : members.keySet()) {
            result.add(members.get(username));
        }
        return result;
    }

    public String name() {
        return name;
    }

    public String fullName() {
        return fullName;
    }

    public Contributor lead() {
        return lead;
    }

    public boolean contains(String username) {
        return members.containsKey(username);
    }

    static Group parse(Path file, Map<String, Contributor> contributors) throws IOException {
        var document = XML.parse(file);
        var group = XML.child(document, "group");
        var name = XML.attribute(group, "name");
        var fullName = XML.attribute(group, "full-name");

        var leadUsername = XML.attribute(XML.child(group, "lead"), "username");
        if (!contributors.containsKey(leadUsername)) {
            contributors.put(leadUsername, new Contributor(leadUsername));
        }
        var lead = contributors.get(leadUsername);

        var members = new ArrayList<Contributor>();
        for (var member : XML.children(group, "member")) {
            var username = XML.attribute(member, "username");
            if (!contributors.containsKey(username)) {
                contributors.put(username, new Contributor(username));
            }
            members.add(contributors.get(username));
        }

        return new Group(name, fullName, lead, members);
    }

    static Group parse(Element ele, Map<String, Contributor> contributors) throws IOException {
        var name = XML.attribute(ele, "name");
        var fullName = XML.child(ele, "full-name").getTextContent();

        Contributor lead = null;
        var members = new ArrayList<Contributor>();
        for (var person : XML.children(ele, "person")) {
            var username = XML.attribute(person, "ref");
            if (!contributors.containsKey(username)) {
                contributors.put(username, new Contributor(username));
            }

            if (XML.hasAttribute(person, "role")) {
                if (!XML.attribute(person, "role").equals("lead")) {
                    throw new IOException("Unexpected role: " + XML.attribute(person, "role"));
                }
                lead = contributors.get(username);
            } else {
                members.add(contributors.get(username));
            }
        }

        return new Group(name, fullName, lead, members);
    }

    @Override
    public String toString() {
        return name + " (" + fullName + ")";
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, fullName);
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof Group other) {
            return Objects.equals(name, other.name) &&
                   Objects.equals(fullName, other.fullName);
        }
        return false;
    }
}
