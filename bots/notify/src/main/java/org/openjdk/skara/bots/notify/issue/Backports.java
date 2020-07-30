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
package org.openjdk.skara.bots.notify.issue;

import org.openjdk.skara.issuetracker.Issue;
import org.openjdk.skara.json.JSONValue;

import java.util.*;
import java.util.logging.Logger;
import java.util.stream.*;

public class Backports {
    private final static Set<String> primaryTypes = Set.of("Bug", "New Feature", "Enhancement", "Task", "Sub-task");
    private final static Logger log = Logger.getLogger("org.openjdk.skara.bots.notify");

    private static boolean isPrimaryIssue(Issue issue) {
        var properties = issue.properties();
        if (!properties.containsKey("issuetype")) {
            throw new RuntimeException("Unknown type for issue " + issue.id());
        }
        var type = properties.get("issuetype");
        return primaryTypes.contains(type.asString());
    }

    private static boolean isNonScratchVersion(String version) {
        return !version.startsWith("tbd") && !version.toLowerCase().equals("unknown");
    }

    private static Set<String> fixVersions(Issue issue) {
        if (!issue.properties().containsKey("fixVersions")) {
            return Set.of();
        }
        return issue.properties().get("fixVersions").stream()
                    .map(JSONValue::asString)
                    .collect(Collectors.toSet());
    }

    /**
     * Returns the single non-scratch fixVersion entry for an issue. If the issue has either none ore more than one,
     * no version is returned.
     * @param issue
     * @return
     */
    static Optional<JdkVersion> mainFixVersion(Issue issue) {
        var versionString = fixVersions(issue).stream()
                                              .filter(Backports::isNonScratchVersion)
                                              .collect(Collectors.toList());
        if (versionString.isEmpty()) {
            return Optional.empty();
        }
        if (versionString.size() > 1) {
            log.warning("Issue " + issue.id() + " has multiple valid fixVersions - ignoring");
            return Optional.empty();
        }
        if (issue.properties().containsKey("customfield_10006") && issue.properties().get("customfield_10006").isObject()) {
            return Optional.of(JdkVersion.parse(versionString.get(0), issue.properties().get("customfield_10006").get("value").asString()));
        } else {
            return Optional.of(JdkVersion.parse(versionString.get(0)));
        }
    }

    /**
     *  Return the main issue for this backport.
     *  Harmless when called with the main issue
     */
    static Optional<Issue> findMainIssue(Issue issue) {
        if (isPrimaryIssue(issue)) {
            return Optional.of(issue);
        }

        for (var link : issue.links()) {
            if (link.issue().isPresent() && link.relationship().isPresent()) {
                if (link.relationship().get().equals("backported by") || link.relationship().get().equals("backport of")) {
                    var linkedIssue = link.issue().get();
                    if (isPrimaryIssue(linkedIssue)) {
                        return Optional.of(linkedIssue);
                    }
                }
            }
        }

        log.warning("Failed to find main issue for " + issue.id());
        return Optional.empty();
    }

    /**
     * Return true if the issue's fixVersionList matches fixVersion.
     *
     * fixVersionsList must contain one entry that is an exact match for fixVersions; any
     * other entries must be scratch values.
     */
    private static boolean matchVersion(Issue issue, JdkVersion fixVersion) {
        var mainVersion = mainFixVersion(issue);
        if (mainVersion.isEmpty()) {
            return false;
        }
        return mainVersion.get().equals(fixVersion);
    }

    /**
     * Return true if the issue's fixVersionList is a match for fixVersion, using "-pool" or "-open".
     *
     * If fixVersion has a major release of <N>, it matches the fixVersionList has an
     * <N>-pool or <N>-open entry and all other entries are scratch values.
     */
    private static boolean matchPoolVersion(Issue issue, JdkVersion fixVersion) {
        var majorVersion = fixVersion.feature();
        var poolVersion = JdkVersion.parse(majorVersion + "-pool");
        var openVersion = JdkVersion.parse(majorVersion + "-open");

        var mainVersion = mainFixVersion(issue);
        if (mainVersion.isEmpty()) {
            return false;
        }
        return mainVersion.get().equals(poolVersion) || mainVersion.get().equals(openVersion);
    }

    /**
     * Return true if fixVersionList is empty or contains only scratch values.
     */
    private static boolean matchScratchVersion(Issue issue) {
        var nonScratch = fixVersions(issue).stream()
                                           .filter(Backports::isNonScratchVersion)
                                           .collect(Collectors.toList());
        return nonScratch.size() == 0;
    }

    /**
     * Return issue or one of its backports that applies to fixVersion.
     *
     * If the main issue       has the correct fixVersion, use it.
     * If an existing Backport has the correct fixVersion, use it.
     * If the main issue       has a matching <N>-pool/open fixVersion, use it.
     * If an existing Backport has a matching <N>-pool/open fixVersion, use it.
     * If the main issue       has a "scratch" fixVersion, use it.
     * If an existing Backport has a "scratch" fixVersion, use it.
     *
     * Otherwise, create a new Backport.
     *
     * A "scratch" fixVersion is empty, "tbd.*", or "unknown".
     */
    static Optional<Issue> findIssue(Issue primary, JdkVersion fixVersion) {
        log.fine("Searching for properly versioned issue for primary issue " + primary.id());
        var candidates = Stream.concat(Stream.of(primary), findBackports(primary).stream()).collect(Collectors.toList());
        candidates.forEach(c -> log.fine("Candidate: " + c.id() + " with versions: " + String.join(",", fixVersions(c))));
        var matchingVersionIssue = candidates.stream()
                                             .filter(i -> matchVersion(i, fixVersion))
                                             .findFirst();
        if (matchingVersionIssue.isPresent()) {
            log.fine("Issue " + matchingVersionIssue.get().id() + " has a correct fixVersion");
            return matchingVersionIssue;
        }

        var matchingPoolVersionIssue = candidates.stream()
                                                 .filter(i -> matchPoolVersion(i, fixVersion))
                                                 .findFirst();
        if (matchingPoolVersionIssue.isPresent()) {
            log.fine("Issue " + matchingPoolVersionIssue.get().id() + " has a matching pool version");
            return matchingPoolVersionIssue;
        }

        var matchingScratchVersionIssue = candidates.stream()
                                                    .filter(Backports::matchScratchVersion)
                                                    .findFirst();
        if (matchingScratchVersionIssue.isPresent()) {
            log.fine("Issue " + matchingScratchVersionIssue.get().id() + " has a scratch fixVersion");
            return matchingScratchVersionIssue;
        }

        log.fine("No suitable existing issue for " + primary.id() + " with version " + fixVersion + " found");
        return Optional.empty();
    }

    static List<Issue> findBackports(Issue primary) {
        var links = primary.links();
        return links.stream()
                    .filter(l -> l.issue().isPresent())
                    .map(l -> l.issue().get())
                    .filter(i -> i.properties().containsKey("issuetype"))
                    .filter(i -> i.properties().get("issuetype").asString().equals("Backport"))
                    .collect(Collectors.toList());
    }

    /**
     * Classifies a given version as belonging to one or more release streams.
     *
     * For the JDK 7 and 8 release trains, this is determined by the feature version (8 in 8u240 for example)
     * combined with the build number. Build numbers between 31 and 60 are considered to be part of the bpr stream.
     *
     * For JDK 9 and subsequent releases, release streams branch into Oracle and OpenJDK updates after the second
     * update version is released. Oracle updates that has a patch version are considered to be part of the bpr stream.
     * @param jdkVersion
     * @return
     */
    private static List<String> releaseStreams(JdkVersion jdkVersion) {
        var ret = new ArrayList<String>();
        try {
            var numericFeature = Integer.parseInt(jdkVersion.feature());
            if (numericFeature >= 9) {
                if (jdkVersion.update().isPresent()) {
                    var numericUpdate = Integer.parseInt(jdkVersion.update().get());
                    if (numericUpdate == 1 || numericUpdate == 2) {
                        ret.add(jdkVersion.feature() + "+updates-oracle");
                        ret.add(jdkVersion.feature() + "+updates-openjdk");
                    } else if (numericUpdate > 2) {
                        if (jdkVersion.opt().isPresent() && jdkVersion.opt().get().equals("oracle")) {
                            if (jdkVersion.patch().isPresent()) {
                                ret.add(jdkVersion.feature()+ "+bpr");
                            } else {
                                ret.add(jdkVersion.feature() + "+updates-oracle");
                            }
                        } else {
                            ret.add(jdkVersion.feature() + "+updates-openjdk");
                        }
                    }
                } else {
                    ret.add("features");
                    ret.add(jdkVersion.feature() + "+updates-oracle");
                    ret.add(jdkVersion.feature() + "+updates-openjdk");
                }
            } else if (numericFeature == 7 || numericFeature == 8) {
                var resolvedInBuild = jdkVersion.resolvedInBuild();
                if (resolvedInBuild.isPresent()) {
                    if (!resolvedInBuild.get().equals("team")) { // Special case - team build resolved are ignored
                        int resolvedInBuildNumber = jdkVersion.resolvedInBuildNumber();
                        if (resolvedInBuildNumber < 31) {
                            ret.add(jdkVersion.feature());
                        } else if (resolvedInBuildNumber < 60) {
                            ret.add(jdkVersion.feature() + "+bpr");
                        }
                    }
                } else {
                    ret.add(jdkVersion.feature());
                }
            } else {
                log.warning("Ignoring issue with unknown version: " + jdkVersion);
            }
        } catch (NumberFormatException ignored) {
            log.info("Cannot determine release streams for version: " + jdkVersion);
        }
        return ret;
    }

    // Split the issue list depending on the release stream
    private static List<List<Issue>> groupByReleaseStream(List<Issue> issues) {
        var streamIssues = new HashMap<String, List<Issue>>();
        for (var issue : issues) {
            var fixVersion = mainFixVersion(issue);
            if (fixVersion.isEmpty()) {
                log.info("Issue " + issue.id() + " does not a fixVersion set - ignoring");
                continue;
            }
            var streams = releaseStreams(fixVersion.get());
            for (var stream : streams) {
                if (!streamIssues.containsKey(stream)) {
                    streamIssues.put(stream, new ArrayList<Issue>());
                }
                streamIssues.get(stream).add(issue);
            }
        }

        var ret = new ArrayList<List<Issue>>();
        for (var issuesInStream : streamIssues.values()) {
            if (issuesInStream.size() < 2) {
                // It's not a release stream unless it has more than one entry
                continue;
            }
            issuesInStream.sort(Comparator.comparing(i -> mainFixVersion(i).orElseThrow()));
            ret.add(issuesInStream);
        }
        return ret;
    }

    /**
     * Applies a label to later releases in a release stream.
     *
     * The label should not be applied to the first release in a specific stream where a fix ships. I.e.
     * it should only be applied to issues in any given stream if the fix version of the issue *is not* the first
     * release where the fix has shipped *within that stream*.
     *
     * @param issue
     * @param label
     */
    static void labelReleaseStreamDuplicates(Issue issue, String label) {
        var mainIssue = Backports.findMainIssue(issue);
        if (mainIssue.isEmpty()) {
            return;
        }
        var related = Backports.findBackports(mainIssue.get());

        var allIssues = new ArrayList<Issue>();
        allIssues.add(mainIssue.get());
        allIssues.addAll(related);

        for (var streamIssues : groupByReleaseStream(allIssues)) {
            // First entry should not have the label
            var first = streamIssues.get(0);
            if (first.labels().contains(label)) {
                first.removeLabel(label);
            }

            // But all the following ones should
            if (streamIssues.size() > 1) {
                var rest = streamIssues.subList(1, streamIssues.size());
                for (var i : rest) {
                    if (!i.labels().contains(label)) {
                        i.addLabel(label);
                    }
                }
            }
        }
    }
}
