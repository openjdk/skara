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
import java.util.stream.Collectors;

public class CommitMessageFormatters {
    public static class V0 implements CommitMessageFormatter {
        public List<String> format(CommitMessage message) {
            if (message.title() != null) {
                throw new IllegalArgumentException("Can't format title, must use issues as title");
            }
            if (message.issues().isEmpty()) {
                throw new IllegalArgumentException("Must supply at least one issue");
            }

            var lines = new ArrayList<String>();

            for (var issue : message.issues()) {
                lines.add(issue.toShortString());
            }
            for (var summary : message.summaries()) {
                lines.add("Summary: " + summary);
            }
            if (message.reviewers().size() > 0) {
                lines.add("Reviewed-by: " + String.join(", ", message.reviewers()));
            }
            if (message.contributors().size() > 0) {
                lines.add("Contributed-by: " + message.contributors()
                                                      .stream()
                                                      .map(Author::toString)
                                                      .collect(Collectors.joining(", ")));
            }

            return lines;
        }
    }

    public static class V1 implements CommitMessageFormatter {
        public List<String> format(CommitMessage message) {
            if (message.title() != null && !message.issues().isEmpty()) {
                throw new IllegalArgumentException("Can't format both title and issues");
            }

            var lines = new ArrayList<String>();

            if (message.title() != null) {
                lines.add(message.title());
            } else {
                for (var issue : message.issues()) {
                    lines.add(issue.toShortString());
                }
            }

            if (message.summaries().size() > 0) {
                lines.add("");
                for (var summary : message.summaries()) {
                    lines.add(summary);
                }
            }

            if ((message.reviewers().size() + message.contributors().size()) > 0) {
                lines.add("");
                if (message.contributors().size() > 0) {
                    for (var contributor : message.contributors()) {
                        lines.add("Co-authored-by: " + contributor.toString());
                    }
                }
                if (message.reviewers().size() > 0) {
                    lines.add("Reviewed-by: " + String.join(", ", message.reviewers()));
                }
            }

            return lines;
        }
    }

    public static CommitMessageFormatter v0 = new V0();
    public static CommitMessageFormatter v1 = new V1();
}
