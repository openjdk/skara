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
package org.openjdk.skara.bots.pr;

import org.openjdk.skara.host.HostUser;
import org.openjdk.skara.issuetracker.Comment;
import org.openjdk.skara.jcheck.JCheckConfiguration;
import org.openjdk.skara.vcs.*;

import java.io.IOException;
import java.util.*;

public class AdditionalConfiguration {
    static List<String> get(ReadOnlyRepository repository, Hash hash, HostUser botUser, List<Comment> comments) throws IOException {
        var currentConfiguration = JCheckConfiguration.from(repository, hash);
        var currentReviewers = currentConfiguration.checks().reviewers();
        var ret = new ArrayList<String>();
        var additionalReviewers = ReviewersTracker.additionalRequiredReviewers(botUser, comments);
        for (var additionalReviewer : additionalReviewers.entrySet()) {
            ret.add("[checks \"reviewers\"]");
            var role = additionalReviewer.getKey();
            switch (role) {
                case "lead":
                    ret.add("lead=" + (currentReviewers.lead() + additionalReviewer.getValue()));
                    break;
                case "reviewers":
                    ret.add("reviewers=" + (currentReviewers.reviewers() + additionalReviewer.getValue()));
                    break;
                case "committers":
                    ret.add("committers=" + (currentReviewers.committers() + additionalReviewer.getValue()));
                    break;
                case "authors":
                    ret.add("authors=" + (currentReviewers.authors() + additionalReviewer.getValue()));
                    break;
                case "contributors":
                    ret.add("contributors=" + (currentReviewers.contributors() + additionalReviewer.getValue()));
                    break;
                default:
                    break;
            }
        }
        return ret;
    }
}
