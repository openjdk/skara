/*
 * Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
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

import org.openjdk.skara.forge.*;
import org.openjdk.skara.issuetracker.Issue;
import org.openjdk.skara.issuetracker.*;
import org.openjdk.skara.jcheck.JCheckConfiguration;
import org.openjdk.skara.vcs.*;
import org.openjdk.skara.vcs.openjdk.*;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.*;

public class IssueUpdater implements RepositoryUpdateConsumer, PullRequestUpdateConsumer {
    private final IssueProject issueProject;
    private final boolean reviewLink;
    private final URI reviewIcon;
    private final boolean commitLink;
    private final URI commitIcon;
    private final boolean setFixVersion;
    private final String fixVersion;
    private final Logger log = Logger.getLogger("org.openjdk.skara.bots.notify");

    IssueUpdater(IssueProject issueProject, boolean reviewLink, URI reviewIcon, boolean commitLink, URI commitIcon,boolean setFixVersion, String fixVersion) {
        this.issueProject = issueProject;
        this.reviewLink = reviewLink;
        this.reviewIcon = reviewIcon;
        this.commitLink = commitLink;
        this.commitIcon = commitIcon;
        this.setFixVersion = setFixVersion;
        this.fixVersion = fixVersion;
    }

    private final static Set<String> primaryTypes = Set.of("Bug", "New Feature", "Enhancement", "Task", "Sub-task");

    private boolean isPrimaryIssue(Issue issue) {
        var properties = issue.properties();
        if (!properties.containsKey("type")) {
            throw new RuntimeException("Unknown type for issue " + issue);
        }
        var type = properties.get("type");
        return primaryTypes.contains(type);
    }

    private final static Pattern majorVersionPattern = Pattern.compile("([0-9]+)(u[0-9]+)?");

    /**
     * Extracts the major version part of the string, if possible.
     */
    private Optional<String> majorVersion(String version) {
        var matcher = majorVersionPattern.matcher(version);
        if (matcher.matches()) {
            return Optional.of(matcher.group(1));
        } else {
            return Optional.empty();
        }
    }

    private List<Issue> findBackports(Issue primary) {
        var links = primary.links();
        return links.stream()
                    .filter(l -> l.issue().isPresent())
                    .map(l -> l.issue().get())
                    .filter(i -> i.properties().containsKey("type"))
                    .filter(i -> i.properties().get("type").equals("Backport"))
                    .collect(Collectors.toList());
    }

    private boolean isNonScratchVersion(String version) {
        return !version.startsWith("tbd") && !version.toLowerCase().equals("unknown");
    }

    /**
     * Return true if the issue's fixVersionList matches fixVersion.
     *
     * fixVersionsList must contain one entry that is an exact match for fixVersions; any
     * other entries must be scratch values.
     */
    private boolean matchVersion(Issue issue, String fixVersion) {
        var nonScratch = issue.fixVersions().stream()
                              .filter(this::isNonScratchVersion)
                              .collect(Collectors.toList());
        return nonScratch.size() == 1 && nonScratch.get(0).equals(fixVersion);
    }

    /**
     * Return True if the issue's fixVersionList is a match for fixVersion, using "-pool" or "-open".
     *
     * If fixVersion has a major release of <N>, it matches the fixVersionList has an
     * <N>-pool or <N>-open entry and all other entries are scratch values.
     */
    private boolean matchPoolVersion(Issue issue, String fixVersion) {
        var majorVersion = majorVersion(fixVersion);
        if (majorVersion.isEmpty()) {
            return false;
        }
        var poolVersion = majorVersion.get() + "-pool";
        var openVersion = majorVersion.get() + "-open";

        var nonScratch = issue.fixVersions().stream()
                              .filter(this::isNonScratchVersion)
                              .collect(Collectors.toList());
        return nonScratch.size() == 1 && (nonScratch.get(0).equals(poolVersion) || nonScratch.get(0).equals(openVersion));
    }

    /**
     * Return True if fixVersionList is empty or contains only scratch values.
     */
    private boolean matchScratchVersion(Issue issue) {
        var nonScratch = issue.fixVersions().stream()
                              .filter(this::isNonScratchVersion)
                              .collect(Collectors.toList());
        return nonScratch.size() == 0;
    }

    /**
     * Create a backport of issue.
     */
    private Issue createBackportIssue(Issue primary) {
        var properties = primary.properties();
        properties.put("type", "Backport");

        var backport = primary.project().createIssue(primary.title(), primary.body().lines().collect(Collectors.toList()), properties);

        var backportLink = Link.create(backport, "backported by").build();
        primary.addLink(backportLink);;
        return backport;
    }

    /**
     * Return issue or one of its backports that applies to fixver.
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
    private Issue findIssue(Issue primary, String fixVersion) {
        log.info("Searching for properly versioned issue for primary issue " + primary.id());
        var candidates = Stream.concat(Stream.of(primary), findBackports(primary).stream()).collect(Collectors.toList());
        candidates.forEach(c -> log.fine("Candidate: " + c.id() + " with versions: " + String.join(",", c.fixVersions())));
        var matchingVersionIssue = candidates.stream()
                .filter(i -> matchVersion(i, fixVersion))
                .findFirst();
        if (matchingVersionIssue.isPresent()) {
            log.info("Issue " + matchingVersionIssue.get().id() + " has a correct fixVersion");
            return matchingVersionIssue.get();
        }

        var matchingPoolVersionIssue = candidates.stream()
                .filter(i -> matchPoolVersion(i, fixVersion))
                .findFirst();
        if (matchingPoolVersionIssue.isPresent()) {
            log.info("Issue " + matchingPoolVersionIssue.get().id() + " has a matching pool version");
            return matchingPoolVersionIssue.get();
        }

        var matchingScratchVersionIssue = candidates.stream()
                .filter(this::matchScratchVersion)
                .findFirst();
        if (matchingScratchVersionIssue.isPresent()) {
            log.info("Issue " + matchingScratchVersionIssue.get().id() + " has a scratch fixVersion");
            return matchingScratchVersionIssue.get();
        }

        log.info("Creating new backport for " + primary.id());
        return createBackportIssue(primary);
    }

    @Override
    public void handleCommits(HostedRepository repository, Repository localRepository, List<Commit> commits, Branch branch) {
        for (var commit : commits) {
            var commitNotification = CommitFormatters.toTextBrief(repository, commit);
            var commitMessage = CommitMessageParsers.v1.parse(commit);
            for (var commitIssue : commitMessage.issues()) {
                var optionalIssue = issueProject.issue(commitIssue.id());
                if (optionalIssue.isEmpty()) {
                    log.severe("Cannot update issue " + commitIssue.id() + " with commit " + commit.hash().abbreviate()
                                       + " - issue not found in issue project");
                    continue;
                }
                var issue = optionalIssue.get();

                // We only update primary type issues
                if (!isPrimaryIssue(issue)) {
                    log.severe("Issue " + issue.id() + " isn't of a primary type - ignoring");
                    // TODO: search for the primary issue
                    continue;
                }

                // The actual issue to be updated can change depending on the fix version
                String requestedVersion = null;
                if (setFixVersion) {
                    requestedVersion = fixVersion;
                    if (requestedVersion == null) {
                        try {
                            var conf = localRepository.lines(Path.of(".jcheck/conf"), commit.hash());
                            if (conf.isPresent()) {
                                var parsed = JCheckConfiguration.parse(conf.get());
                                var version = parsed.general().version();
                                requestedVersion = version.orElse(null);
                            }
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }

                    if (requestedVersion != null) {
                        issue = findIssue(issue, requestedVersion);
                    }
                }

                var existingComments = issue.comments();
                var hashUrl = repository.webUrl(commit.hash()).toString();
                var alreadyPostedComment = existingComments.stream()
                                                           .filter(comment -> comment.author().equals(issueProject.issueTracker().currentUser()))
                                                           .anyMatch(comment -> comment.body().contains(hashUrl));
                if (!alreadyPostedComment) {
                    issue.addComment(commitNotification);
                }
                issue.setState(Issue.State.RESOLVED);

                if (commitLink) {
                    var linkBuilder = Link.create(repository.webUrl(commit.hash()), "Commit")
                                          .summary(repository.name() + "/" + commit.hash().abbreviate());
                    if (commitIcon != null) {
                        linkBuilder.iconTitle("Commit");
                        linkBuilder.iconUrl(commitIcon);
                    }
                    issue.addLink(linkBuilder.build());
                }

                if (setFixVersion) {
                    if (requestedVersion != null) {
                        for (var oldVersion : issue.fixVersions()) {
                            issue.removeFixVersion(oldVersion);
                        }
                        issue.addFixVersion(requestedVersion);
                    }
                }
            }
        }
    }

    @Override
    public void handleOpenJDKTagCommits(HostedRepository repository, Repository localRepository, List<Commit> commits, OpenJDKTag tag, Tag.Annotated annotated) {

    }

    @Override
    public void handleTagCommit(HostedRepository repository, Repository localRepository, Commit commit, Tag tag, Tag.Annotated annotation) {

    }

    @Override
    public void handleNewBranch(HostedRepository repository, Repository localRepository, List<Commit> commits, Branch parent, Branch branch) {

    }

    @Override
    public boolean isIdempotent() {
        return true;
    }

    @Override
    public void handleNewIssue(PullRequest pr, org.openjdk.skara.vcs.openjdk.Issue issue) {
        var realIssue = issueProject.issue(issue.id());
        if (realIssue.isEmpty()) {
            log.warning("Pull request " + pr + " added unknown issue: " + issue.id());
            return;
        }

        if (reviewLink) {
            var linkBuilder = Link.create(pr.webUrl(), "Review")
                                  .summary(pr.repository().name() + "/" + pr.id());
            if (reviewIcon != null) {
                linkBuilder.iconTitle("Review");
                linkBuilder.iconUrl(reviewIcon);
            }

            realIssue.get().addLink(linkBuilder.build());
        }
    }

    @Override
    public void handleRemovedIssue(PullRequest pr, org.openjdk.skara.vcs.openjdk.Issue issue) {
        var realIssue = issueProject.issue(issue.id());
        if (realIssue.isEmpty()) {
            log.warning("Pull request " + pr + " removed unknown issue: " + issue.id());
            return;
        }

        var link = Link.create(pr.webUrl(), "").build();
        realIssue.get().removeLink(link);
    }
}
