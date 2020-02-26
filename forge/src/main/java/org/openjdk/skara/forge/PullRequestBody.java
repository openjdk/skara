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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class PullRequestBody {
    private final List<URI> issues;
    private final List<String> contributors;

    private PullRequestBody(List<URI> issues, List<String> contributors) {
        this.issues = issues;
        this.contributors = contributors;
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

    public static PullRequestBody parse(List<String> lines) {
        var issues = new ArrayList<URI>();
        var contributors = new ArrayList<String>();

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
        }

        return new PullRequestBody(issues, contributors);
    }
}
