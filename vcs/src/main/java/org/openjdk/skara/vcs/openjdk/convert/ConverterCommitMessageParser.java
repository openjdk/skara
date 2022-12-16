/*
 * Copyright (c) 2019, 2022, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.vcs.openjdk.convert;

import java.util.List;
import java.util.ArrayList;

import org.openjdk.skara.vcs.*;
import org.openjdk.skara.vcs.openjdk.*;

import static org.openjdk.skara.vcs.openjdk.CommitMessageSyntax.*;

public class ConverterCommitMessageParser implements CommitMessageParser {
    public CommitMessage parse(List<String> message) {
        var issues = new ArrayList<Issue>();
        var reviewers = new ArrayList<String>();
        var contributors = new ArrayList<Author>();
        var summaries = new ArrayList<String>();
        var additional = new ArrayList<String>();

        for (var line : message) {
            var m = ISSUE_PATTERN.matcher(line);
            if (m.matches()) {
                var id = m.group(1);
                var desc = m.group(2);
                issues.add(new Issue(id, desc));
                continue;
            }

            m = REVIEWED_BY_PATTERN.matcher(line);
            if (m.matches()) {
                for (var name : m.group(1).split(", ")) {
                    reviewers.add(name);
                }
                continue;
            }

            m = SUMMARY_PATTERN.matcher(line);
            if (m.matches()) {
                summaries.add(m.group(1));
                continue;
            }

            m = CONTRIBUTED_BY_PATTERN.matcher(line);
            if (m.matches()) {
                for (var attribution : m.group(1).split(", ")) {
                    if (attribution.contains(" ")) {
                        // must be 'Real Name <email>' variant
                        contributors.add(Author.fromString(attribution));
                    } else {
                        // must be the email only variant
                        contributors.add(new Author("", attribution));
                    }
                }
                continue;
            }

            additional.add(line);
        }

        return new CommitMessage(null, issues, reviewers, contributors, summaries, null, additional);
    }
}
