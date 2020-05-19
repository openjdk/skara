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

import org.openjdk.skara.email.EmailAddress;
import org.openjdk.skara.forge.*;
import org.openjdk.skara.issuetracker.*;
import org.openjdk.skara.jcheck.JCheckConfiguration;
import org.openjdk.skara.json.*;
import org.openjdk.skara.vcs.*;
import org.openjdk.skara.vcs.openjdk.CommitMessageParsers;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.*;

public class IssueUpdater implements RepositoryUpdateConsumer, PullRequestUpdateConsumer {
    private final IssueProject issueProject;
    private final boolean reviewLink;
    private final URI reviewIcon;
    private final boolean commitLink;
    private final URI commitIcon;
    private final boolean setFixVersion;
    private final Map<String, String> fixVersions;
    private final boolean prOnly;
    private final Logger log = Logger.getLogger("org.openjdk.skara.bots.notify");

    IssueUpdater(IssueProject issueProject, boolean reviewLink, URI reviewIcon, boolean commitLink, URI commitIcon,
                 boolean setFixVersion, Map<String, String> fixVersions, boolean prOnly) {
        this.issueProject = issueProject;
        this.reviewLink = reviewLink;
        this.reviewIcon = reviewIcon;
        this.commitLink = commitLink;
        this.commitIcon = commitIcon;
        this.setFixVersion = setFixVersion;
        this.fixVersions = fixVersions;
        this.prOnly = prOnly;
    }

    static IssueUpdaterBuilder newBuilder() {
        return new IssueUpdaterBuilder();
    }

    private final static Set<String> primaryTypes = Set.of("Bug", "New Feature", "Enhancement", "Task", "Sub-task");

    private boolean isPrimaryIssue(Issue issue) {
        var properties = issue.properties();
        if (!properties.containsKey("issuetype")) {
            throw new RuntimeException("Unknown type for issue " + issue.id());
        }
        var type = properties.get("issuetype");
        return primaryTypes.contains(type.asString());
    }

    /**
     *  Return the main issue for this backport.
     *  Harmless when called with the main issue
     */
    private Issue findMainIssue(Issue issue) {
        if (isPrimaryIssue(issue)) {
            return issue;
        }

        for (var link : issue.links()) {
            if (link.issue().isPresent()) {
                var linkedIssue = link.issue().get();
                if (isPrimaryIssue(linkedIssue)) {
                    return linkedIssue;
                }
            }
        }

        log.warning("Failed to find main issue for " + issue.id());
        return issue;
    }

    private List<Issue> findBackports(Issue primary) {
        var links = primary.links();
        return links.stream()
                    .filter(l -> l.issue().isPresent())
                    .map(l -> l.issue().get())
                    .filter(i -> i.properties().containsKey("issuetype"))
                    .filter(i -> i.properties().get("issuetype").asString().equals("Backport"))
                    .collect(Collectors.toList());
    }

    private boolean isNonScratchVersion(String version) {
        return !version.startsWith("tbd") && !version.toLowerCase().equals("unknown");
    }

    private Set<String> fixVersions(Issue issue) {
        if (!issue.properties().containsKey("fixVersions")) {
            return Set.of();
        }
        return issue.properties().get("fixVersions").stream()
                    .map(JSONValue::asString)
                    .collect(Collectors.toSet());
    }

    /**
     * Return true if the issue's fixVersionList matches fixVersion.
     *
     * fixVersionsList must contain one entry that is an exact match for fixVersions; any
     * other entries must be scratch values.
     */
    private boolean matchVersion(Issue issue, Version fixVersion) {
        var nonScratch = fixVersions(issue).stream()
                                           .filter(this::isNonScratchVersion)
                                           .collect(Collectors.toList());
        return nonScratch.size() == 1 && Version.parse(nonScratch.get(0)).equals(fixVersion);
    }

    /**
     * Return true if the issue's fixVersionList is a match for fixVersion, using "-pool" or "-open".
     *
     * If fixVersion has a major release of <N>, it matches the fixVersionList has an
     * <N>-pool or <N>-open entry and all other entries are scratch values.
     */
    private boolean matchPoolVersion(Issue issue, Version fixVersion) {
        var majorVersion = fixVersion.feature();
        var poolVersion = majorVersion + "-pool";
        var openVersion = majorVersion + "-open";

        var nonScratch = fixVersions(issue).stream()
                                           .filter(this::isNonScratchVersion)
                                           .collect(Collectors.toList());
        return nonScratch.size() == 1 && (nonScratch.get(0).equals(poolVersion) || nonScratch.get(0).equals(openVersion));
    }

    /**
     * Return true if fixVersionList is empty or contains only scratch values.
     */
    private boolean matchScratchVersion(Issue issue) {
        var nonScratch = fixVersions(issue).stream()
                                           .filter(this::isNonScratchVersion)
                                           .collect(Collectors.toList());
        return nonScratch.size() == 0;
    }

    private final static Set<String> propagatedCustomProperties =
            Set.of("customfield_10008", "customfield_10000", "customfield_10005");

    /**
     * Create a backport of issue.
     */
    private Issue createBackportIssue(Issue primary) throws NonRetriableException {
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
    private Issue findIssue(Issue primary, Version fixVersion) throws NonRetriableException {
        log.info("Searching for properly versioned issue for primary issue " + primary.id());
        var candidates = Stream.concat(Stream.of(primary), findBackports(primary).stream()).collect(Collectors.toList());
        candidates.forEach(c -> log.fine("Candidate: " + c.id() + " with versions: " + String.join(",", fixVersions(c))));
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

    private Optional<String> findIssueUsername(Commit commit) {
        var authorEmail = EmailAddress.from(commit.author().email());
        if (authorEmail.domain().equals("openjdk.org")) {
            return Optional.of(authorEmail.localPart());
        }

        var committerEmail = EmailAddress.from(commit.committer().email());
        if (!committerEmail.domain().equals("openjdk.org")) {
            log.severe("Cannot determine issue tracker user name from committer email: " + committerEmail);
            return Optional.empty();
        }
        return Optional.of(committerEmail.localPart());
    }

    private Optional<Version> mainFixVersion(Issue issue) {
        var versionString = fixVersions(issue).stream()
                                              .filter(this::isNonScratchVersion)
                                              .findAny();
        if (versionString.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(Version.parse(versionString.get()));
    }

    // Split the issue list depending on the line of development
    private List<List<Issue>> groupByLOD(List<Issue> issues) {
        var grouped = issues.stream()
                            .map(issue -> new AbstractMap.SimpleEntry<Issue, Version>(issue, mainFixVersion(issue).orElse(null)))
                            .filter(entry -> entry.getValue() != null)
                            .collect(Collectors.groupingBy(entry -> entry.getValue().lineOfDevelopment()));

        return grouped.values().stream()
                      .map(entries -> entries.stream()
                                             .sorted(Map.Entry.comparingByValue())
                                             .map(AbstractMap.SimpleEntry::getKey)
                                             .collect(Collectors.toList()))
                      .collect(Collectors.toList());
    }

    // Give all issues related to the same change (except the first one) a specific label that indicates that
    // it is a duplicate. This allows easier issue filtering.
    private void labelDuplicates(Issue issue, String label) {
        var mainIssue = findMainIssue(issue);
        var related = findBackports(mainIssue);

        var allIssues = new ArrayList<Issue>();
        allIssues.add(mainIssue);
        allIssues.addAll(related);

        for (var lod : groupByLOD(allIssues)) {
            // First entry should not have the label
            var first = lod.get(0);
            if (first.labels().contains(label)) {
                first.removeLabel(label);
            }

            // But all the following ones should
            if (lod.size() > 1) {
                var rest = lod.subList(1, lod.size());
                for (var i : rest) {
                    if (!i.labels().contains(label)) {
                        i.addLabel(label);
                    }
                }
            }
        }
    }

    @Override
    public void handleCommits(HostedRepository repository, Repository localRepository, List<Commit> commits, Branch branch) throws NonRetriableException {
        for (var commit : commits) {
            var commitNotification = CommitFormatters.toTextBrief(repository, commit);
            var commitMessage = CommitMessageParsers.v1.parse(commit);
            for (var commitIssue : commitMessage.issues()) {
                var optionalIssue = issueProject.issue(commitIssue.shortId());
                if (optionalIssue.isEmpty()) {
                    log.severe("Cannot update issue " + commitIssue.id() + " with commit " + commit.hash().abbreviate()
                                       + " - issue not found in issue project");
                    continue;
                }
                var issue = optionalIssue.get();

                // We only update primary type issues
                if (!isPrimaryIssue(issue)) {
                    var candidate = findMainIssue(issue);
                    if (!isPrimaryIssue(candidate) || candidate.id().equals(issue.id())) {
                        log.severe("Issue " + issue.id() + " isn't of a primary type - ignoring");
                        continue;
                    } else {
                        log.warning("Issue " + issue.id() + " isn't of a primary type - using " + candidate.id() + " instead");
                        issue = candidate;
                    }
                }

                String requestedVersion = null;
                if (prOnly) {
                    var candidates = repository.findPullRequestsWithComment(null, "Pushed as commit " + commit.hash() + ".");
                    if (candidates.size() != 1) {
                        log.info("IssueUpdater@" + issue.id() + ": Skipping commit " + commit.hash().abbreviate() + " for repository " + repository.name() +
                                         " on branch " + branch.name() + " - " + candidates.size() + " matching PRs found (needed 1)");
                        continue;
                    }
                    var candidate = candidates.get(0);
                    var prLink = candidate.webUrl();
                    if (!candidate.targetRef().equals(branch.name())) {
                        log.info("IssueUpdater@" + issue.id() + ": Pull request " + prLink + " targets " + candidate.targetRef() + " - commit is on " + branch.toString() + " - skipping");
                        continue;
                    }
                } else {
                    // The actual issue to be updated can change depending on the fix version
                    if (setFixVersion) {
                        requestedVersion = fixVersions != null ? fixVersions.getOrDefault(branch.name(), null) : null;
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
                            issue = findIssue(issue, Version.parse(requestedVersion));
                        }
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
                if (issue.state() == Issue.State.OPEN) {
                    issue.setState(Issue.State.RESOLVED);
                    if (issue.assignees().isEmpty()) {
                        var username = findIssueUsername(commit);
                        if (username.isPresent()) {
                            var assignee = issueProject.issueTracker().user(username.get());
                            if (assignee.isPresent()) {
                                issue.setAssignees(List.of(assignee.get()));
                            }
                        }
                    }
                }

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
                        issue.setProperty("fixVersions", JSON.of(requestedVersion));
                    }
                }

                if (1 == 1) {
                    labelDuplicates(issue, "hgupdater-sync");
                }
            }
        }
    }

    @Override
    public String name() {
        return "issue";
    }

    @Override
    public void handleNewIssue(PullRequest pr, org.openjdk.skara.vcs.openjdk.Issue issue) {
        var realIssue = issueProject.issue(issue.shortId());
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
        var realIssue = issueProject.issue(issue.shortId());
        if (realIssue.isEmpty()) {
            log.warning("Pull request " + pr + " removed unknown issue: " + issue.id());
            return;
        }

        var link = Link.create(pr.webUrl(), "").build();
        realIssue.get().removeLink(link);
    }
}
