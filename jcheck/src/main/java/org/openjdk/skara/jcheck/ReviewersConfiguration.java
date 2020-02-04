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
package org.openjdk.skara.jcheck;

import org.openjdk.skara.ini.Section;

import java.util.List;
import java.util.stream.Collectors;

public class ReviewersConfiguration {
    static final ReviewersConfiguration DEFAULT = new ReviewersConfiguration(0, 1, 0, 0, 0, List.of("duke"));

    private final int lead;
    private final int reviewers;
    private final int committers;
    private final int authors;
    private final int contributors;
    private final List<String> ignore;

    ReviewersConfiguration(int lead, int reviewers, int committers, int authors, int contributors, List<String> ignore) {
        this.lead = lead;
        this.reviewers = reviewers;
        this.committers = committers;
        this.authors = authors;
        this.contributors = contributors;
        this.ignore = ignore;
    }

    public int lead() {
        return lead;
    }

    public int reviewers() {
        return reviewers;
    }

    public int committers() {
        return committers;
    }

    public int authors() {
        return authors;
    }

    public int contributors() {
        return contributors;
    }

    public List<String> ignore() {
        return ignore;
    }

    static String name() {
        return "reviewers";
    }

    static ReviewersConfiguration parse(Section s) {
        if (s == null) {
            return DEFAULT;
        }

        var lead = s.get("lead", 0);
        var reviewers = s.get("reviewers", 0);
        var committers = s.get("committers", 0);
        var authors = s.get("authors", 0);
        var contributors = s.get("contributors", 0);

        if (s.contains("minimum")) {
            // Reset defaults to 0
            lead = 0;
            reviewers = 0;
            committers = 0;
            authors = 0;
            contributors = 0;

            var minimum = s.get("minimum").asInt();
            if (s.contains("role")) {
                var role = s.get("role").asString();
                if (role.equals("lead")) {
                    lead = minimum;
                } else if (role.equals("reviewer")) {
                    reviewers = minimum;
                } else if (role.equals("committer")) {
                    committers = minimum;
                } else if (role.equals("author")) {
                    authors = minimum;
                } else if (role.equals("contributor")) {
                    contributors = minimum;
                } else {
                    throw new IllegalArgumentException("Unexpected role: " + role);
                }
            } else {
                reviewers = minimum;
            }
        }

        var ignore = s.get("ignore", DEFAULT.ignore());

        return new ReviewersConfiguration(lead, reviewers, committers, authors, contributors, ignore);
    }
}
