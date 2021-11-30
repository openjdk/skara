/*
 * Copyright (c) 2020, 2021, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.jbs;

import org.openjdk.skara.issuetracker.Issue;
import org.openjdk.skara.json.JSONValue;

import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Pattern;
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
    public static Optional<JdkVersion> mainFixVersion(Issue issue) {
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
        if (issue.properties().containsKey("customfield_10006")) {
            return JdkVersion.parse(versionString.get(0), issue.properties().get("customfield_10006").asString());
        } else {
            return JdkVersion.parse(versionString.get(0));
        }
    }

    /**
     *  Return the main issue for this backport.
     *  Harmless when called with the main issue
     */
    public static Optional<Issue> findMainIssue(Issue issue) {
        if (isPrimaryIssue(issue)) {
            return Optional.of(issue);
        }

        var links = issue.linksWithRelationships(List.of("backported by", "backport of"));
        if (links == null || links.isEmpty()) {
            return Optional.empty();
        }
        for (var link : links) {
            var linkedIssue = link.issue().orElse(null);
            if (linkedIssue != null && isPrimaryIssue(linkedIssue)) {
                return Optional.of(linkedIssue);
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
     * If fixVersion has a major release of <N>, and opt string of <opt> it matches if
     * the fixVersionList has an <N>-pool-<opt> entry.
     */
    private static boolean matchOptPoolVersion(Issue issue, JdkVersion fixVersion) {
        var majorVersion = fixVersion.feature();
        if (fixVersion.opt().isPresent()) {
            var poolSuffix = "-pool-" + fixVersion.opt().get();
            var poolVersion = JdkVersion.parse(majorVersion + poolSuffix);
            // fixVersion may be something that doesn't parse into a valid pool version
            if (poolVersion.isPresent()) {
                var mainVersion = mainFixVersion(issue);
                if (mainVersion.isEmpty()) {
                    return false;
                }
                return mainVersion.get().equals(poolVersion.get());
            }
        }
        return false;
    }

    /**
     * If fixVersion has a major release of <N>, it matches if the fixVersionList has an
     * <N>-pool entry.
     */
    private static boolean matchPoolVersion(Issue issue, JdkVersion fixVersion) {
        var majorVersion = fixVersion.feature();
        var poolVersion = JdkVersion.parse(majorVersion + "-pool");
        // fixVersion may be something that doesn't parse into a valid pool version
        if (poolVersion.isPresent()) {
            var mainVersion = mainFixVersion(issue);
            if (mainVersion.isEmpty()) {
                return false;
            }
            return mainVersion.get().equals(poolVersion.get());
        }
        return false;
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
     * If the main issue       has a matching <N>-pool fixVersion, use it.
     * If an existing Backport has a matching <N>-pool fixVersion, use it.
     * If the main issue       has a "scratch" fixVersion, use it.
     * If an existing Backport has a "scratch" fixVersion, use it.
     *
     * Otherwise, create a new Backport.
     *
     * A "scratch" fixVersion is empty, "tbd.*", or "unknown".
     */
    public static Optional<Issue> findIssue(Issue primary, JdkVersion fixVersion) {
        log.fine("Searching for properly versioned issue for primary issue " + primary.id());
        var candidates = Stream.concat(Stream.of(primary), findBackports(primary, false).stream()).collect(Collectors.toList());
        candidates.forEach(c -> log.fine("Candidate: " + c.id() + " with versions: " + String.join(",", fixVersions(c))));
        var matchingVersionIssue = candidates.stream()
                                             .filter(i -> matchVersion(i, fixVersion))
                                             .findFirst();
        if (matchingVersionIssue.isPresent()) {
            log.fine("Issue " + matchingVersionIssue.get().id() + " has a correct fixVersion");
            return matchingVersionIssue;
        }

        var matchingOptPoolVersionIssue = candidates.stream()
                .filter(i -> matchOptPoolVersion(i, fixVersion))
                .findFirst();
        if (matchingOptPoolVersionIssue.isPresent()) {
            log.fine("Issue " + matchingOptPoolVersionIssue.get().id() + " has a matching opt pool version");
            return matchingOptPoolVersionIssue;
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

    private static boolean isFixed(Issue issue) {
        if (issue.state() == Issue.State.OPEN) {
            return false;
        }
        var resolution = issue.properties().get("resolution");
        if (resolution == null || resolution.isNull()) {
            return false;
        }
        var name = resolution.get("name");
        if (name == null || name.isNull()) {
            return false;
        }

        if (!name.asString().equals("Fixed")) {
            return false;
        }

        return true;
    }

    public static List<Issue> findBackports(Issue primary, boolean fixedOnly) {
        var links = primary.links();
        return links.stream()
                    .filter(l -> l.issue().isPresent())
                    .filter(l -> l.relationship().isPresent())
                    .filter(l -> l.relationship().get().equals("backported by"))
                    .map(l -> l.issue().get())
                    .filter(i -> !fixedOnly || isFixed(i))
                    // We used to filter out any issues not of 'backport' type here, but
                    // Jira allows linking of any issues with a 'backported by' link, so we
                    // have to accept them, even if it's weird.
                    .collect(Collectors.toList());
    }

    private static final Pattern featureFamilyPattern = Pattern.compile("^([^\\d]*)(\\d*)$");

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
            var featureFamilyMatcher = featureFamilyPattern.matcher(jdkVersion.feature());
            if (!featureFamilyMatcher.matches()) {
                log.warning("Cannot parse feature family: " + jdkVersion.feature());
                return ret;
            }
            var featureFamily = featureFamilyMatcher.group(1);
            var featureVersion = featureFamilyMatcher.group(2);
            var numericFeature = Integer.parseInt(featureVersion);
            if (numericFeature >= 9) {
                if (jdkVersion.update().isPresent()) {
                    var numericUpdate = Integer.parseInt(jdkVersion.update().get());
                    if (numericUpdate == 1 || numericUpdate == 2) {
                        if (jdkVersion.opt().isPresent() && jdkVersion.opt().get().equals("oracle") && jdkVersion.components().size() > 4) {
                            ret.add(jdkVersion.feature() + "+bpr");
                        } else {
                            ret.add(jdkVersion.feature() + "+updates-oracle");
                            ret.add(jdkVersion.feature() + "+updates-openjdk");
                        }
                    } else if (numericUpdate > 2) {
                        if (jdkVersion.opt().isPresent() && jdkVersion.opt().get().equals("oracle")) {
                            if (jdkVersion.components().size() > 4) {
                                ret.add(jdkVersion.feature()+ "+bpr");
                            } else {
                                ret.add(jdkVersion.feature() + "+updates-oracle");
                            }
                        } else {
                            ret.add(jdkVersion.feature() + "+updates-openjdk");
                        }
                    }
                } else {
                    ret.add("features-" + featureFamily);
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
        } catch (NumberFormatException e) {
            log.info("Cannot determine release streams for version: " + jdkVersion + " (" + e + ")");
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
                    streamIssues.put(stream, new ArrayList<>());
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

    // Certain versions / build numbers have a special meaning, and should be excluded from stream processing
    private static boolean onExcludeList(Issue issue) {
        var fixVersion = mainFixVersion(issue);
        if (fixVersion.isEmpty()) {
            return false;
        }

        var version = fixVersion.get();

        // 8u260 and 8u270 are contingency releases
        if (version.raw().equals("8u260")) {
            return true;
        }
        if (version.raw().equals("8u270")) {
            return true;
        }

        // 8u41 to 8u44 are reserved for JSR maintenance releases
        if (version.feature().equals("8") && version.interim().isPresent() && Integer.parseInt(version.interim().get()) >= 41 && Integer.parseInt(version.interim().get()) <= 44) {
            return true;
        }

        // JEP-322 interim releases (second digit > 0) should be excluded from evaluation
        var featureFamilyMatcher = featureFamilyPattern.matcher(version.feature());
        if (featureFamilyMatcher.matches()) {
            var featureVersion = featureFamilyMatcher.group(2);
            if (featureVersion.length() > 0) {
                var numericFeature = Integer.parseInt(featureVersion);

                if (numericFeature >= 9 && version.interim().isPresent() && !version.interim().get().equals("0")) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Returns release stream duplicate issue. I.e.
     * it will contain issues in any given stream if the fix version of the issue *is not* the first
     * release where the fix has shipped *within that stream*.
     *
     * @param related
     */
    public static List<Issue> releaseStreamDuplicates(List<Issue> related) {
        var ret = new ArrayList<Issue>();

        var includedOnly = related.stream()
                .filter(issue -> !onExcludeList(issue))
                .collect(Collectors.toList());

        for (var streamIssues : groupByReleaseStream(includedOnly)) {
            // The first issue may have the label if it was part of another
            // stream. (e.g. feature release has 14 & 15 where update release
            // has 15, 15.0.1 & 15.0.2. In this case the label should be
            // applied to 15, which is the first releases in the 15u stream)
            // This means we ignore the first issue for the purposes of adding
            // the label.
            if (streamIssues.size() > 1) {
                var rest = streamIssues.subList(1, streamIssues.size());
                ret.addAll(rest);
            }
        }

        return ret;
    }
}
