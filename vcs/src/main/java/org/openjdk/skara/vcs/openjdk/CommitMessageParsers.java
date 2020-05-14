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
package org.openjdk.skara.vcs.openjdk;

import org.openjdk.skara.vcs.Author;

import java.util.*;
import java.util.regex.*;
import java.util.stream.Collectors;

import static org.openjdk.skara.vcs.openjdk.CommitMessageSyntax.*;

public class CommitMessageParsers {
    private static Matcher matcher(Pattern p, List<String> lines, int index) {
        if (index >= lines.size()) {
            return null;
        }

        var m = p.matcher(lines.get(index));
        return m.matches() ? m : null;
    }

    public static class V0 implements CommitMessageParser {
        public CommitMessage parse(List<String> lines) {
            var i = 0;
            Matcher m = null;

            var issues = new ArrayList<Issue>();
            while ((m = matcher(ISSUE_PATTERN, lines, i)) != null) {
                var id = m.group(1);
                var desc = m.group(2);
                issues.add(new Issue(id, desc));
                i++;
            }

            var summaries = new ArrayList<String>();
            while ((m = matcher(SUMMARY_PATTERN, lines, i)) != null) {
                summaries.add(m.group(1));
                i++;
            }

            var reviewers = new ArrayList<String>();
            while ((m = matcher(REVIEWED_BY_PATTERN, lines, i)) != null) {
                for (var name : m.group(1).split(", ")) {
                    reviewers.add(name);
                }
                i++;
            }

            var contributors = new ArrayList<Author>();
            while ((m = matcher(CONTRIBUTED_BY_PATTERN, lines, i)) != null) {
                for (var attribution : m.group(1).split(", ")) {
                    if (attribution.contains(" ")) {
                        // must be 'Real Name <email>' variant
                        contributors.add(Author.fromString(attribution));
                    } else {
                        // must be the email only variant
                        contributors.add(new Author("", attribution));
                    }
                }
                i++;
            }

            var additional = lines.subList(i, lines.size());
            return new CommitMessage(null, issues, reviewers, contributors, summaries, additional);
        }
    }

    public static class V1 implements CommitMessageParser {
        public CommitMessage parse(List<String> lines) {
            var i = 0;
            Matcher m = null;

            var issues = new ArrayList<Issue>();
            while ((m = matcher(ISSUE_PATTERN, lines, i)) != null) {
                var id = m.group(1);
                var desc = m.group(2);
                issues.add(new Issue(id, desc));
                i++;
            }

            String title = null;
            if (issues.size() == 0 && i < lines.size()) {
                // the first line wasn't an issue, treat is a generic title
                title = lines.get(0);
                i++;
            } else {
                title = issues.stream().map(Issue::toShortString).collect(Collectors.joining("\n"));
            }

            var firstDelimiter = true;
            var summaries = new ArrayList<String>();
            var coAuthors = new ArrayList<Author>();
            var reviewers = new ArrayList<String>();
            while (i < lines.size() && lines.get(i).equals("")) {
                i++;

                if (matcher(CO_AUTHOR_PATTERN, lines, i) != null ||
                    matcher(REVIEWED_BY_PATTERN, lines, i) != null) {
                    // "trailers" section
                    while ((m = matcher(CO_AUTHOR_PATTERN, lines, i)) != null) {
                        for (var author : m.group(1).split(", ")) {
                            coAuthors.add(Author.fromString(author));
                        }
                        i++;
                    }

                    if ((m = matcher(REVIEWED_BY_PATTERN, lines, i)) != null) {
                        for (var name : m.group(1).split(", ")) {
                            reviewers.add(name);
                        }
                        i++;
                    }

                    break; // there should be no more lines after the "trailers"
                }

                if (!firstDelimiter) {
                    summaries.add("");
                } else {
                    firstDelimiter = false;
                }
                while (i < lines.size() && !lines.get(i).equals("")) {
                    summaries.add(lines.get(i));
                    i++;
                }
            }

            var additional = lines.subList(i, lines.size());
            return new CommitMessage(title, issues, reviewers, coAuthors, summaries, additional);
        }
    }

    public static CommitMessageParser v0 = new V0();
    public static CommitMessageParser v1 = new V1();
}
