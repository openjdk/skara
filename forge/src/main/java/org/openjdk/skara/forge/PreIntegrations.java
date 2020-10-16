package org.openjdk.skara.forge;

import java.util.Optional;

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

    public static void retargetDependencies(PullRequest pr) {
        var dependentRef = preIntegrateBranch(pr);

        var candidates = pr.repository().pullRequests();
        for (var candidate : candidates) {
            if (candidate.targetRef().equals(dependentRef)) {
                candidate.setTargetRef(pr.targetRef());
            }
        }
    }

    public static boolean isPreintegrationBranch(String name) {
        return name.startsWith("pr/");
    }
}
