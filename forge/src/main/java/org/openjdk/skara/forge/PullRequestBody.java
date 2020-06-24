/*
 * Copyright (c) 2020, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.forge;

import java.net.URI;
import java.util.*;
import java.util.regex.Pattern;

public class PullRequestBody {
    private final String bodyText;
    private final List<URI> issues;
    private final List<String> contributors;

    private static final Pattern cutoffPattern = Pattern.compile("^<!-- Anything below this marker will be .*? -->$");

    private PullRequestBody(String bodyText, List<URI> issues, List<String> contributors) {
        this.bodyText = bodyText;
        this.issues = issues;
        this.contributors = contributors;
    }

    public String bodyText() {
        return bodyText;
    }

    public List<URI> issues() {
        return issues;
    }

    public List<String> contributors() {
        return contributors;
    }

    public static PullRequestBody parse(PullRequest pr) {
        return parse(Arrays.asList(pr.body().split("\n")));
    }

    public static PullRequestBody parse(String body) {
        return parse(Arrays.asList(body.split("\n")));
    }

    public static PullRequestBody parse(List<String> lines) {
        var issues = new ArrayList<URI>();
        var contributors = new ArrayList<String>();
        var bodyText = new StringBuilder();
        var inBotComment = false;

        var i = 0;
        while (i < lines.size()) {
            var line = lines.get(i);
            if (line.startsWith("### Issue")) {
                i++;
                while (i < lines.size()) {
                    line = lines.get(i);
                    if (!line.startsWith(" * ")) {
                        break;
                    }
                    var startUrl = line.indexOf('(');
                    var endUrl = line.indexOf(')', startUrl);
                    if (startUrl != -1 && endUrl != -1) {
                        var url = URI.create(line.substring(startUrl + 1, endUrl));
                        issues.add(url);
                    }
                    i++;
                }
            }
            if (line.startsWith("### Contributors")) {
                i++;
                while (i < lines.size()) {
                    line = lines.get(i);
                    if (!line.startsWith(" * ")) {
                        break;
                    }
                    var contributor = line.substring(3).replace("`","");
                    contributors.add(contributor);
                    i++;
                }
            } else {
                i++;
            }
            if (line.startsWith("<!-- Anything below this marker will be")) {
                inBotComment = true;
            }
            if (!inBotComment) {
                bodyText.append(line).append("\n");
            }
        }

        return new PullRequestBody(bodyText.toString(), issues, contributors);
    }
}
