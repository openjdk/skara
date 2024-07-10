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

public class Project {
    private final String name;
    private final String fullName;
    private final Group sponsor;

    private final Map<String, Member> leaders = new HashMap<>();
    private final Map<String, Member> reviewers = new HashMap<>();
    private final Map<String, Member> committers = new HashMap<>();
    private final Map<String, Member> authors = new HashMap<>();

    private void populate(Map<String, Member> category, List<Member> members) {
        for (var member : members) {
            category.put(member.username(), member);
        }
    }

    Project(String name,
            String fullName,
            Group sponsor,
            List<Member> leaders,
            List<Member> reviewers,
            List<Member> committers,
            List<Member> authors) {
        this.name = name;
        this.fullName = fullName;
        this.sponsor = sponsor;

        populate(this.leaders, leaders);
        populate(this.reviewers, reviewers);
        populate(this.committers, committers);
        populate(this.authors, authors);
    }

    public String name() {
        return name;
    }

    public String fullName() {
        return fullName;
    }

    public Group sponsor() {
        return sponsor;
    }

    private boolean isMember(Map<String, Member> category, String username, int version) {
        if (!category.containsKey(username)) {
            return false;
        }

        var member = category.get(username);
        return version >= member.since() && version < member.until();
    }

    public boolean isLead(String username, int version) {
        return isMember(leaders, username, version);
    }

    public boolean isReviewer(String username, int version) {
        return isLead(username, version) ||
               isMember(reviewers, username, version);
    }

    public boolean isCommitter(String username, int version) {
        return isReviewer(username, version) ||
               isMember(committers, username, version);
    }

    public boolean isAuthor(String username, int version) {
        return isCommitter(username, version) ||
               isMember(authors, username, version);
    }

    private Set<Contributor> members(Map<String, Member> category, int version) {
        var result = new HashSet<Contributor>();
        for (var username : category.keySet()) {
            if (isMember(category, username, version)) {
                result.add(category.get(username).contributor());
            }
        }
        return result;
    }

    public Map<String, Set<Contributor>> roles(int version) {
        var res = new HashMap<String, Set<Contributor>>();
        var lead = lead(version);
        res.put("lead", lead == null ? Set.of() : Set.of(lead));
        res.put("reviewer", members(reviewers, version));
        res.put("committer", members(committers, version));
        res.put("author", members(authors, version));
        return res;
    }

    public Contributor lead(int version) {
        var leadersAtVersion = members(leaders, version);
        if (leadersAtVersion.size() != 1) {
            return null;
        }
        return leadersAtVersion.iterator().next();
    }

    public Set<Contributor> reviewers(int version) {
        var leaderAtVersion = lead(version);
        var reviewersAtVersion = members(reviewers, version);
        if (leaderAtVersion != null) {
            reviewersAtVersion.add(leaderAtVersion);
        }
        return reviewersAtVersion;
    }

    public Set<Contributor> committers(int version) {
        var reviewersAtVersion = reviewers(version);
        var committersAtVersion = members(committers, version);
        committersAtVersion.addAll(reviewersAtVersion);
        return committersAtVersion;
    }

    public Set<Contributor> authors(int version) {
        var committersAtVersion = committers(version);
        var authorsAtVersion = members(authors, version);
        authorsAtVersion.addAll(committersAtVersion);
        return authorsAtVersion;
    }

    static Project parse(Path file, Map<String, Group> groups, Map<String, Contributor> contributors) throws IOException {
        var document = XML.parse(file);
        var project = XML.child(document, "project");
        var name = XML.attribute(project, "name");
        var fullName = XML.attribute(project, "full-name");
        var sponsorName = XML.attribute(project, "sponsor");
        if (!groups.containsKey(sponsorName)) {
            throw new IllegalArgumentException("Unknown group " + sponsorName);
        }
        var sponsor = groups.get(sponsorName);

        var leaders = Parser.members(XML.children(project, "lead"), contributors);
        var reviewers = Parser.members(XML.children(project, "reviewer"), contributors);
        var committers = Parser.members(XML.children(project, "committer"), contributors);
        var authors = Parser.members(XML.children(project, "author"), contributors);

        return new Project(name, fullName, sponsor, leaders, reviewers, committers, authors);
    }

    static Project parse(Element ele, Map<String, Group> groups, Map<String, Contributor> contributors) throws IOException {
        var name = XML.attribute(ele, "name");
        var fullName = XML.child(ele, "full-name").getTextContent();

        var sponsorName = XML.attribute(XML.child(ele, "sponsor"), "ref");
        if (!groups.containsKey(sponsorName)) {
            throw new IllegalArgumentException("Unknown group " + sponsorName);
        }
        var sponsor = groups.get(sponsorName);

        var leaders = new ArrayList<Member>();
        var committers = new ArrayList<Member>();
        var reviewers = new ArrayList<Member>();
        var authors = new ArrayList<Member>();

        for (var person : XML.children(ele, "person")) {
            var username = XML.attribute(person, "ref");
            if (!contributors.containsKey(username)) {
                contributors.put(username, new Contributor(username));
            }
            var member = new Member(contributors.get(username), 0);

            switch (XML.attribute(person, "role")) {
                case "lead":
                    leaders.add(member);
                    break;
                case "reviewer":
                    reviewers.add(member);
                    break;
                case "committer":
                    committers.add(member);
                    break;
                case "author":
                    authors.add(member);
                    break;
                default:
                    if ((username.equals("dwookey") || username.equals("jpereda")) &&
                        name.equals("openjfx")) {
                        authors.add(member);
                    } else {
                        throw new IOException("Unexpected role for " + username +
                                              " in project " + name + ": '" + XML.attribute(person, "role") + "'");
                    }
            }
        }

        return new Project(name, fullName, sponsor, leaders, reviewers, committers, authors);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, fullName, sponsor, leaders, reviewers, committers, authors);
    }

    @Override
    public String toString() {
        return name + " (" + fullName + ")";
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Project p)) {
            return false;
        }

        return Objects.equals(name, p.name) &&
               Objects.equals(fullName, p.fullName) &&
               Objects.equals(sponsor, p.sponsor) &&
               Objects.equals(leaders, p.leaders) &&
               Objects.equals(reviewers, p.reviewers) &&
               Objects.equals(committers, p.committers) &&
               Objects.equals(authors, p.authors);
    }
}
