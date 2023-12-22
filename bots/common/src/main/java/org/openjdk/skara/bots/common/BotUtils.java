/*
 * Copyright (c) 2022, 2023, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.bots.common;

import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * This class contains utility methods used by more than one bot. These methods
 * can't reasonably be located in the various libraries as they combine
 * functionality and knowledge unique to bot applications. As this class grows,
 * it should be encouraged to split it up into more cohesive units.
 */
public class BotUtils {
    private static final String lineSep = "(?:\\n|\\r|\\r\\n|\\n\\r)";
    private static final Pattern issuesBlockPattern = Pattern.compile(lineSep + lineSep + "###? Issues?((?:" + lineSep + "(?: \\* )?\\[.*)+)", Pattern.MULTILINE);
    private static final Pattern issuePattern = Pattern.compile("^(?: \\* )?\\[(\\S+)]\\(.*\\): (.*$)", Pattern.MULTILINE);

    public static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("@", "@<!---->");
    }

    /**
     * Parses issues from a pull request body and filters out JEP and CSR issues
     *
     * @param body The Pull Request Body
     * @return Set of issue ids
     */
    public static Set<String> parseIssues(String body) {
        var issuesBlockMatcher = issuesBlockPattern.matcher(body);
        if (!issuesBlockMatcher.find()) {
            return Set.of();
        }
        var issueMatcher = issuePattern.matcher(issuesBlockMatcher.group(1));
        return issueMatcher.results()
                .filter(mr -> !mr.group(2).endsWith(" (**CSR**)") && !mr.group(2).endsWith(" (**CSR**) (Withdrawn)") && !mr.group(2).endsWith(" (**JEP**)"))
                .map(mo -> mo.group(1))
                .collect(Collectors.toSet());
    }

    /**
     * Parses issues from a pull request body.
     *
     * @param body The pull request body
     * @return Set of issue ids
     */
    public static Set<String> parseAllIssues(String body) {
        var issuesBlockMatcher = issuesBlockPattern.matcher(body);
        if (!issuesBlockMatcher.find()) {
            return Set.of();
        }
        var issueMatcher = issuePattern.matcher(issuesBlockMatcher.group(1));
        return issueMatcher.results()
                .map(mo -> mo.group(1))
                .collect(Collectors.toSet());
    }

    public static String preprocessCommandLine(String line) {
        return line.replaceFirst("/skara\\s+", "/");
    }
}
