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
package org.openjdk.skara.bots.notify;

import org.openjdk.skara.issuetracker.*;
import org.openjdk.skara.json.*;

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
    static Optional<Version> mainFixVersion(Issue issue) {
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
        return Optional.of(Version.parse(versionString.get(0)));
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
    private static boolean matchVersion(Issue issue, Version fixVersion) {
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
    private static boolean matchPoolVersion(Issue issue, Version fixVersion) {
        var majorVersion = fixVersion.feature();
        var poolVersion = Version.parse(majorVersion + "-pool");
        var openVersion = Version.parse(majorVersion + "-open");

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
    static Optional<Issue> findIssue(Issue primary, Version fixVersion) {
        log.info("Searching for properly versioned issue for primary issue " + primary.id());
        var candidates = Stream.concat(Stream.of(primary), findBackports(primary).stream()).collect(Collectors.toList());
        candidates.forEach(c -> log.fine("Candidate: " + c.id() + " with versions: " + String.join(",", fixVersions(c))));
        var matchingVersionIssue = candidates.stream()
                                             .filter(i -> matchVersion(i, fixVersion))
                                             .findFirst();
        if (matchingVersionIssue.isPresent()) {
            log.info("Issue " + matchingVersionIssue.get().id() + " has a correct fixVersion");
            return matchingVersionIssue;
        }

        var matchingPoolVersionIssue = candidates.stream()
                                                 .filter(i -> matchPoolVersion(i, fixVersion))
                                                 .findFirst();
        if (matchingPoolVersionIssue.isPresent()) {
            log.info("Issue " + matchingPoolVersionIssue.get().id() + " has a matching pool version");
            return matchingPoolVersionIssue;
        }

        var matchingScratchVersionIssue = candidates.stream()
                                                    .filter(Backports::matchScratchVersion)
                                                    .findFirst();
        if (matchingScratchVersionIssue.isPresent()) {
            log.info("Issue " + matchingScratchVersionIssue.get().id() + " has a scratch fixVersion");
            return matchingScratchVersionIssue;
        }

        log.info("No suitable existing issue for " + primary.id() + " with version " + fixVersion + " found");
        return Optional.empty();
    }

    private final static Set<String> propagatedCustomProperties =
            Set.of("customfield_10008", "customfield_10000", "customfield_10005");

    /**
     * Create a backport of issue.
     */
    static Issue createBackportIssue(Issue primary) throws NonRetriableException {
        var filteredProperties = primary.properties().entrySet().stream()
                                        .filter(entry -> !entry.getKey().startsWith("customfield_") || propagatedCustomProperties.contains(entry.getKey()))
                                        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        var finalProperties = new HashMap<>(filteredProperties);
        finalProperties.put("issuetype", JSON.of("Backport"));

        try {
            var backport = primary.project().createIssue(primary.title(), primary.body().lines().collect(Collectors.toList()), finalProperties);

            var backportLink = Link.create(backport, "backported by").build();
            primary.addLink(backportLink);
            return backport;
        } catch (RuntimeException e) {
            throw new NonRetriableException(e);
        }
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
}
