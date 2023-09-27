/*
 * Copyright (c) 2020, 2023, Oracle and/or its affiliates. All rights reserved.
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

import java.util.*;

public class PreIntegrations {
    public static Optional<String> dependentPullRequestId(PullRequest pr) {
        if (isPreintegrationBranch(pr.targetRef())) {
            var depStart = pr.targetRef().lastIndexOf("/");
            if (depStart == -1) {
                throw new IllegalStateException("Cannot parse target ref: " + pr.targetRef());
            }
            var depId = pr.targetRef().substring(depStart + 1);
            return Optional.of(depId);
        } else {
            return Optional.empty();
        }
    }

    public static String preIntegrateBranch(PullRequest pr) {
        return "pr/" + pr.id();
    }

    public static Collection<PullRequest> retargetDependencies(PullRequest pr) {
        var ret = new ArrayList<PullRequest>();
        var dependentRef = preIntegrateBranch(pr);

        var candidates = pr.repository().openPullRequests();
        for (var candidate : candidates) {
            if (candidate.targetRef().equals(dependentRef)) {
                candidate.setTargetRef(pr.targetRef());
                ret.add(candidate);
            }
        }
        return ret;
    }

    public static boolean isPreintegrationBranch(String name) {
        return name.startsWith("pr/");
    }

    public static String realTargetRef(PullRequest pr) {
        Optional<String> idOpt = dependentPullRequestId(pr);
        return idOpt.isEmpty() ? pr.targetRef() : realTargetRef(pr.repository().pullRequest(idOpt.get()));
    }
}
