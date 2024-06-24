/*
 * Copyright (c) 2020, 2024, Oracle and/or its affiliates. All rights reserved.
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

import org.openjdk.skara.issuetracker.IssueTrackerIssue;
import org.openjdk.skara.issuetracker.Link;
import org.openjdk.skara.json.JSON;
import org.openjdk.skara.json.JSONValue;

import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.*;

import static org.openjdk.skara.issuetracker.jira.JiraProject.RESOLVED_IN_BUILD;

public class Backports {
    private final static Set<String> primaryTypes = Set.of("Bug", "New Feature", "Enhancement", "Task", "Sub-task");
    private final static Logger log = Logger.getLogger("org.openjdk.skara.bots.notify");

    private static final Pattern FEATURE_FAMILY_PATTERN = Pattern.compile("^([^\\d]*)(\\d*)$");

    private static boolean isPrimaryIssue(IssueTrackerIssue issue) {
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

    public static Set<String> fixVersions(IssueTrackerIssue issue) {
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
    public static Optional<JdkVersion> mainFixVersion(IssueTrackerIssue issue) {
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
        if (issue.properties().containsKey(RESOLVED_IN_BUILD)) {
            return JdkVersion.parse(versionString.get(0), issue.properties().get(RESOLVED_IN_BUILD).asString());
        } else {
            return JdkVersion.parse(versionString.get(0));
        }
    }

    /**
     *  Return the main issue for this backport.
     *  Harmless when called with the main issue
     */
    public static Optional<IssueTrackerIssue> findMainIssue(IssueTrackerIssue issue) {
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
     * Return true if issueVersion matches fixVersion.
     */
    private static boolean matchVersion(JdkVersion issueVersion, JdkVersion fixVersion) {
        return issueVersion.equals(fixVersion);
    }

    /**
     * If fixVersion has a major release of <N>, and opt string of <opt> it matches if the issueVersion equals to <N>-pool-<opt>.
     */
    private static boolean matchOptPoolVersion(JdkVersion issueVersion, JdkVersion fixVersion) {
        // Remove any trailing 'u' from the feature version as that isn't used in *-pool versions
        var majorVersion = fixVersion.feature().replaceFirst("u$", "");
        if (fixVersion.opt().isPresent()) {
            var poolSuffix = "-pool-" + fixVersion.opt().get();
            var poolVersion = JdkVersion.parse(majorVersion + poolSuffix);
            // fixVersion may be something that doesn't parse into a valid pool version
            if (poolVersion.isPresent()) {
                return issueVersion.equals(poolVersion.get());
            }
        }
        return false;
    }

    /**
     * If fixVersion has a major release of <N>, it matches if the issueVersion equals to <N>-pool.
     */
    private static boolean matchPoolVersion(JdkVersion issueVersion, JdkVersion fixVersion) {
        // Remove any trailing 'u' from the feature version as that isn't used in *-pool versions
        var majorVersion = fixVersion.feature().replaceFirst("u$", "");
        var poolVersion = JdkVersion.parse(majorVersion + "-pool");
        // fixVersion may be something that doesn't parse into a valid pool version
        if (poolVersion.isPresent()) {
            if (issueVersion.equals(poolVersion.get())) {
                return true;
            }
        }
        var versionMatcher = FEATURE_FAMILY_PATTERN.matcher(majorVersion);
        if (versionMatcher.matches()) {
            var numericMajorVersion = versionMatcher.group(2);
            if (!numericMajorVersion.equals(majorVersion)) {
                var numericPoolVersion = JdkVersion.parse(numericMajorVersion + "-pool");
                if (numericPoolVersion.isPresent()) {
                    if (issueVersion.equals(numericPoolVersion.get())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Return true if issueVersions is empty or contains only scratch values.
     */
    private static boolean matchScratchVersion(Set<String> issueVersions) {
        return issueVersions.stream()
                .noneMatch(Backports::isNonScratchVersion);
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
    public static Optional<IssueTrackerIssue> findIssue(IssueTrackerIssue primary, JdkVersion fixVersion) {
        log.fine("Searching for properly versioned issue for primary issue " + primary.id());
        var candidates = Stream.concat(Stream.of(primary), findBackports(primary, false).stream()).toList();
        candidates.forEach(c -> log.fine("Candidate: " + c.id() + " with versions: " + String.join(",", fixVersions(c))));
        var matchingVersionIssue = candidates.stream()
                .filter(i -> mainFixVersion(i).filter(jdkVersion -> matchVersion(jdkVersion, fixVersion)).isPresent())
                .findFirst();
        if (matchingVersionIssue.isPresent()) {
            log.fine("Issue " + matchingVersionIssue.get().id() + " has a correct fixVersion");
            return matchingVersionIssue;
        }

        var matchingOptPoolVersionIssue = candidates.stream()
                .filter(i -> mainFixVersion(i).filter(jdkVersion -> matchOptPoolVersion(jdkVersion, fixVersion)).isPresent())
                .findFirst();
        if (matchingOptPoolVersionIssue.isPresent()) {
            log.fine("Issue " + matchingOptPoolVersionIssue.get().id() + " has a matching opt pool version");
            return matchingOptPoolVersionIssue;
        }

        var matchingPoolVersionIssue = candidates.stream()
                .filter(i -> mainFixVersion(i).filter(jdkVersion -> matchPoolVersion(jdkVersion, fixVersion)).isPresent())
                .findFirst();
        if (matchingPoolVersionIssue.isPresent()) {
            log.fine("Issue " + matchingPoolVersionIssue.get().id() + " has a matching pool version");
            return matchingPoolVersionIssue;
        }

        var matchingScratchVersionIssue = candidates.stream()
                .filter(i -> matchScratchVersion(fixVersions(i)))
                .findFirst();
        if (matchingScratchVersionIssue.isPresent()) {
            log.fine("Issue " + matchingScratchVersionIssue.get().id() + " has a scratch fixVersion");
            return matchingScratchVersionIssue;
        }

        log.fine("No suitable existing issue for " + primary.id() + " with version " + fixVersion + " found");
        return Optional.empty();
    }

    /**
     * Returns issue or one of its backports that has a fixVersion matching the
     * version pattern and is fixed.
     */
    public static Optional<IssueTrackerIssue> findFixedIssue(IssueTrackerIssue primary, Pattern versionPattern) {
        log.fine("Searching for fixed issue with fix version matching /" + versionPattern + "/ "
                + " for primary issue " + primary.id());
        return Stream.concat(Stream.of(primary).filter(IssueTrackerIssue::isFixed), findBackports(primary, true).stream())
                .filter(i -> mainFixVersion(i).map(v -> versionPattern.matcher(v.raw()).matches()).orElse(false))
                .findFirst();
    }

    /**
     * Find the closest issue from the provided issue list according to the provided fix version.
     * This method is similar to `findIssue`, but this method can handle all the fix versions of the issue
     * instead of only the main fix version and can receive an issue list instead of only the primary issue.
     *
     * If one of the issues has the correct fix version, use it.
     * Else, if one of the issues has a matching <N>-pool-<opt> fix version, use it.
     * Else, if one of the issues has a matching <N>-pool fix version, use it.
     * Else, if one of the issues has a "scratch" fix version, use it.
     * Otherwise, return empty.
     *
     * A "scratch" fixVersion is empty, "tbd.*", or "unknown".
     */
    public static Optional<IssueTrackerIssue> findClosestIssue(List<IssueTrackerIssue> issueList, JdkVersion fixVersion) {
        var matchingVersionIssue = issueList.stream()
                .filter(issue -> Backports.fixVersions(issue).stream().anyMatch(
                        v -> JdkVersion.parse(v).filter(jdkVersion -> matchVersion(jdkVersion, fixVersion)).isPresent()))
                .findFirst();
        if (matchingVersionIssue.isPresent()) {
            return matchingVersionIssue;
        }

        var matchingOptPoolVersionIssue = issueList.stream()
                .filter(issue -> Backports.fixVersions(issue).stream().anyMatch(
                        v -> JdkVersion.parse(v).filter(jdkVersion -> matchOptPoolVersion(jdkVersion, fixVersion)).isPresent()))
                .findFirst();
        if (matchingOptPoolVersionIssue.isPresent()) {
            return matchingOptPoolVersionIssue;
        }

        var matchingPoolVersionIssue = issueList.stream()
                .filter(issue -> Backports.fixVersions(issue).stream().anyMatch(
                        v -> JdkVersion.parse(v).filter(jdkVersion -> matchPoolVersion(jdkVersion, fixVersion)).isPresent()))
                .findFirst();
        if (matchingPoolVersionIssue.isPresent()) {
            return matchingPoolVersionIssue;
        }

        return issueList.stream()
                .filter(issue -> matchScratchVersion(fixVersions(issue)))
                .findFirst();
    }

    /**
     * Find the right CSR according to the primary issue and the requested version
     */
    public static Optional<IssueTrackerIssue> findCsr(IssueTrackerIssue primary, JdkVersion version) {
        var csrList = new ArrayList<IssueTrackerIssue>();
        csrLink(primary).flatMap(Link::issue).ifPresent(csrList::add);
        for (var backportIssue : Backports.findBackports(primary, false)) {
            csrLink(backportIssue).flatMap(Link::issue).ifPresent(csrList::add);
        }
        return findClosestIssue(csrList, version);
    }

    /**
     * Find the CSR of the provided issue
     */
    public static Optional<Link> csrLink(IssueTrackerIssue issue) {
        return issue == null ? Optional.empty() : issue.links().stream()
                .filter(link -> link.relationship().isPresent() && "csr for".equals(link.relationship().get())).findAny();
    }

    public static List<IssueTrackerIssue> findBackports(IssueTrackerIssue primary, boolean fixedOnly) {
        var links = primary.links();
        return links.stream()
                    .filter(l -> l.issue().isPresent())
                    .filter(l -> l.relationship().isPresent())
                    .filter(l -> l.relationship().get().equals("backported by"))
                    .map(l -> l.issue().get())
                    .filter(i -> !fixedOnly || i.isFixed())
                    // We used to filter out any issues not of 'backport' type here, but
                    // Jira allows linking of any issues with a 'backported by' link, so we
                    // have to accept them, even if it's weird.
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
        List<String> ret = new ArrayList<String>();
        try {
            var featureFamilyMatcher = FEATURE_FAMILY_PATTERN.matcher(jdkVersion.feature());
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
                        } else if (numericFeature <= 11 && jdkVersion.resolvedInBuild().isPresent()
                                && jdkVersion.resolvedInBuildNumber() > 30) {
                            ret.add(jdkVersion.feature() + "+bpr");
                        } else {
                            ret.add(jdkVersion.feature() + "+updates-oracle");
                            ret.add(jdkVersion.feature() + "+updates-openjdk");
                        }
                    } else if (numericUpdate > 2) {
                        if (jdkVersion.opt().isPresent() && jdkVersion.opt().get().equals("oracle")) {
                            if (jdkVersion.components().size() > 4) {
                                ret.add(jdkVersion.feature()+ "+bpr");
                            } else if (numericFeature <= 11 && numericUpdate == 3 && jdkVersion.resolvedInBuild().isPresent()
                                    && jdkVersion.resolvedInBuildNumber() > 30) {
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
            } else if (numericFeature == 7 || numericFeature == 8 || numericFeature == 6) {
                // For update releases, certain ranges of build numbers need special treatment
                if (bprException(jdkVersion, numericFeature)) {
                    ret.add(jdkVersion.feature());
                } else if (jdkVersion.interim().isPresent()) {
                    var resolvedInBuild = jdkVersion.resolvedInBuild();
                    if (resolvedInBuild.isPresent()) {
                        int resolvedInBuildNumber = jdkVersion.resolvedInBuildNumber();
                        if (resolvedInBuildNumber < 30) {
                            ret.add(jdkVersion.feature());
                        } else if (resolvedInBuildNumber < 60) {
                            ret.add(jdkVersion.feature() + "+bpr");
                        }
                    } else {
                        ret.add(jdkVersion.feature());
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
        // For any arbitrary opt string that we haven't already handled explicitly,
        // we let them represent their own respective release streams.
        if (jdkVersion.opt().isPresent()) {
            String opt = jdkVersion.opt().get();
            if (!opt.equals("oracle")) {
                var plusOpt = "+" + opt;
                ret = ret.stream()
                        .map(r -> r + plusOpt)
                        .collect(Collectors.toList());
            }
        }
        return ret;
    }

    /**
     * The general BPR rule cannot be applied to releases that have 30 or more actual builds.
     *
     * @return true if such a release is identified.
     */
    private static boolean bprException(JdkVersion jdkVersion, int numericFeature) {
        if (jdkVersion.interim().isPresent()) {
            var numericInterim = Integer.parseInt(jdkVersion.interim().get());
            if ((numericFeature == 7 && numericInterim == 40) || (numericFeature == 8 && numericInterim == 26)) {
                return true;
            }
        }
        return false;
    }

    // Split the issue list depending on the release stream
    private static List<List<IssueTrackerIssue>> groupByReleaseStream(List<IssueTrackerIssue> issues) {
        var streamIssues = new HashMap<String, List<IssueTrackerIssue>>();
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

        var ret = new ArrayList<List<IssueTrackerIssue>>();
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
    private static boolean onExcludeList(IssueTrackerIssue issue) {
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
        var featureFamilyMatcher = FEATURE_FAMILY_PATTERN.matcher(version.feature());
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
    public static List<IssueTrackerIssue> releaseStreamDuplicates(List<IssueTrackerIssue> related) {
        var ret = new ArrayList<IssueTrackerIssue>();

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

    public static IssueTrackerIssue createBackport(IssueTrackerIssue primary, String fixVersion) {
        return createBackport(primary, fixVersion, null);
    }

    public static IssueTrackerIssue createBackport(IssueTrackerIssue primary, String fixVersion, String assignee) {
        return createBackport(primary, fixVersion, assignee, null);
    }

    public static IssueTrackerIssue createBackport(IssueTrackerIssue primary, String fixVersion, String assignee, String defaultSecurity) {
        var backportEndpoint = primary.project()
                                      .issueTracker()
                                      .lookupCustomEndpoint("/rest/jbs/1.0/backport/")
                                      .orElseThrow(() ->
            new IllegalArgumentException("Issue tracker does not support backport endpoint")
        );
        var body = JSON.object()
                       .put("parentIssueKey", primary.id())
                       .put("fixVersion", fixVersion);

        if (assignee != null) {
            body = body.put("assignee", assignee);
        }

        if (primary.properties().containsKey("security")) {
            body = body.put("level", primary.properties().get("security").asString());
        } else if (defaultSecurity != null) {
            body = body.put("level", defaultSecurity);
        }

        var response = backportEndpoint.post()
                                       .body(body)
                                       .execute();
        var issue = primary.project().issue(response.get("key").asString()).orElseThrow();

        // The backport should not have any labels set - if it does, clear them
        var labels = issue.labelNames();
        if (!labels.isEmpty()) {
            issue.setLabels(List.of());
        }

        return issue;
    }
}
