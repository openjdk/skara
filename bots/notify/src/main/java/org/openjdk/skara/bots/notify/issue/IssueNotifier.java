/*
 * Copyright (c) 2019, 2025, Oracle and/or its affiliates. All rights reserved.
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

import java.util.regex.Pattern;
import org.openjdk.skara.bots.notify.*;
import org.openjdk.skara.email.EmailAddress;
import org.openjdk.skara.forge.*;
import org.openjdk.skara.issuetracker.Issue;
import org.openjdk.skara.issuetracker.*;
import org.openjdk.skara.jbs.*;
import org.openjdk.skara.jcheck.JCheckConfiguration;
import org.openjdk.skara.json.JSON;
import org.openjdk.skara.network.UncheckedRestException;
import org.openjdk.skara.vcs.*;
import org.openjdk.skara.vcs.openjdk.*;

import java.io.*;
import java.net.URI;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;

import static org.openjdk.skara.issuetracker.jira.JiraProject.RESOLVED_IN_BUILD;

class IssueNotifier implements Notifier, PullRequestListener, RepositoryListener {
    private final IssueProject issueProject;
    private final boolean reviewLink;
    private final URI reviewIcon;
    private final boolean commitLink;
    private final URI commitIcon;
    private final boolean setFixVersion;
    private final LinkedHashMap<Pattern, String> fixVersions;
    private final LinkedHashMap<Pattern, List<Pattern>> altFixVersions;
    private final boolean prOnly;
    private final boolean repoOnly;
    private final String buildName;
    private final HostedRepository censusRepository;
    private final String censusRef;
    private final String namespace;
    // If true, use the version found in .jcheck/conf in the HEAD revision instead of the
    // current commit when resolving fixVersion for a new commit.
    private final boolean useHeadVersion;
    // If set, use this repository for generating URLs to commits instead of the one
    // supplied. This can be used to have the bot act on a mirror of the original
    // repository but still generate links to the original. Only works for notifications
    // on repository, not pull requests.
    private final HostedRepository originalRepository;
    // Controls whether the notifier should try to resolve issues. Only valid when
    // pronly is true.
    private final boolean resolve;

    // A set of version opt strings that may be part of fixVersion in issues, but that
    // do not need to be part of a tag to be considered a match.
    private final Set<String> tagIgnoreOpt;

    // Should the prefix of a tag match the prefix of a fix version to be considered
    // a match (except for the special tag prefix 'jdk' which will always be ignored
    // when parsing a version from a tag).
    private final boolean tagMatchPrefix;

    record BranchSecurity(Pattern branch, String securityId) {}
    private final List<BranchSecurity> defaultSecurity;

    private final Logger log = Logger.getLogger("org.openjdk.skara.bots.notify");

    // Lazy loaded
    private CensusInstance census = null;

    // If true, avoid creating a "forward backport" when creating a new backport
    private final boolean avoidForwardports;

    // If true, allow multiple values in the Fix Versions field instead of using
    // backport records for every additional fix version.
    private final boolean multiFixVersions;

    IssueNotifier(IssueProject issueProject, boolean reviewLink, URI reviewIcon, boolean commitLink, URI commitIcon,
                  boolean setFixVersion, LinkedHashMap<Pattern, String> fixVersions, LinkedHashMap<Pattern, List<Pattern>> altFixVersions,
                  boolean prOnly, boolean repoOnly, String buildName,
                  HostedRepository censusRepository, String censusRef, String namespace, boolean useHeadVersion,
                  HostedRepository originalRepository, boolean resolve, Set<String> tagIgnoreOpt,
                  boolean tagMatchPrefix, List<BranchSecurity> defaultSecurity, boolean avoidForwardports,
                  boolean multiFixVersions) {
        this.issueProject = issueProject;
        this.reviewLink = reviewLink;
        this.reviewIcon = reviewIcon;
        this.commitLink = commitLink;
        this.commitIcon = commitIcon;
        this.setFixVersion = setFixVersion;
        this.fixVersions = fixVersions;
        this.altFixVersions = altFixVersions;
        this.prOnly = prOnly;
        this.repoOnly = repoOnly;
        this.buildName = buildName;
        this.censusRepository = censusRepository;
        this.censusRef = censusRef;
        this.namespace = namespace;
        this.useHeadVersion = useHeadVersion;
        this.originalRepository = originalRepository;
        this.resolve = resolve;
        this.tagIgnoreOpt = tagIgnoreOpt;
        this.tagMatchPrefix = tagMatchPrefix;
        this.defaultSecurity = defaultSecurity;
        this.avoidForwardports = avoidForwardports;
        this.multiFixVersions = multiFixVersions;
    }

    static IssueNotifierBuilder newBuilder() {
        return new IssueNotifierBuilder();
    }

    private CensusInstance getCensus() {
        if (census == null) {
            census = CensusInstance.create(censusRepository, censusRef, namespace);
        }
        return census;
    }

    private Optional<String> findCensusUser(String user, Path scratchPath) {
        if (censusRepository == null) {
            return Optional.empty();
        }
        var ns = getCensus().namespace();
        for (var entry : ns.entries()) {
            if (entry.getValue().username().equals(user)) {
                return Optional.of(entry.getKey());
            }
        }
        return Optional.empty();
    }

    private Optional<String> findIssueUsername(Commit commit, Path scratchPath) {
        var authorEmail = EmailAddress.from(commit.author().email());
        if (authorEmail.domain().equals(namespace)) {
            return Optional.of(authorEmail.localPart());
        } else {
            var user = findCensusUser(authorEmail.localPart(), scratchPath);
            if (user.isPresent()) {
                return user;
            }
        }

        var committerEmail = EmailAddress.from(commit.committer().email());
        if (committerEmail.domain().equals("openjdk.org")) {
            return Optional.of(committerEmail.localPart());
        } else {
            var user = findCensusUser(committerEmail.localPart(), scratchPath);
            if (user.isPresent()) {
                return user;
            }

            log.warning("Cannot determine issue tracker user name from committer email: " + committerEmail);
            return Optional.empty();
        }
    }

    @Override
    public void attachTo(Emitter e) {
        if (!repoOnly) {
            e.registerPullRequestListener(this);
        }
        if (!prOnly || buildName != null) {
            e.registerRepositoryListener(this);
        }
    }

    @Override
    public void onIntegratedPullRequest(PullRequest pr, Path scratchPath, Hash hash)  {
        var repository = pr.repository();
        var commit = repository.commit(hash).orElseThrow(() ->
                new IllegalStateException("Integrated commit " + hash +
                                          " not present in repository " + repository.webUrl())
        );
        var commitMessage = CommitMessageParsers.v1.parse(commit);
        for (var commitIssue : commitMessage.issues()) {
            var optionalIssue = issueProject.issue(commitIssue.shortId());
            if (optionalIssue.isEmpty()) {
                log.severe("Cannot update issue " + commitIssue.id() + " with commit " + commit.hash().abbreviate()
                        + " - issue not found in issue project");
                continue;
            }
            var issue = optionalIssue.get();

            if (commitLink) {
                var linkBuilder = Link.create(repository.webUrl(hash), "Commit(" + pr.targetRef() + ")")
                        .summary(repository.name() + "/" + hash.abbreviate());
                if (commitIcon != null) {
                    linkBuilder.iconTitle("Commit");
                    linkBuilder.iconUrl(commitIcon);
                }
                issue.addLink(linkBuilder.build());
            }

            // If prOnly is false, this is instead done when processing commits
            if (prOnly && resolve) {
                log.info("Resolving issue " + issue.id() + " from state " + issue.state());
                if (!issue.isFixed()) {
                    issue.setState(Issue.State.RESOLVED);
                } else {
                    log.info("The issue was already resolved");
                }
                if (issue.assignees().isEmpty()) {
                    var username = findIssueUsername(commit, scratchPath);
                    username.ifPresent(s -> setAssigneeForIssue(issue, s));
                }
            }
        }
    }

    private void setAssigneeForIssue(IssueTrackerIssue issue, String username) {
        var assignee = issueProject.issueTracker().user(username);
        if (assignee.isPresent()) {
            if (assignee.get().active()) {
                log.info("Setting assignee for issue " + issue.id() + " to " + assignee.get());
                issue.setAssignees(List.of(assignee.get()));
            } else {
                log.warning("Skipping setting assignee for issue " + issue.id() + " to " + assignee.get() + " because the user is inactive");
            }
        }
    }

    public void onTargetBranchChange(PullRequest pr, Path scratchPath, org.openjdk.skara.vcs.openjdk.Issue issue) {
        var realIssue = issueProject.issue(issue.shortId());
        if (realIssue.isEmpty()) {
            log.warning("Pull request " + pr + " added unknown issue: " + issue.id());
            return;
        }

        if (reviewLink) {
            // Remove the previous link
            removeReviewLink(pr, realIssue.get());
            // Add a new link
            addReviewLink(pr, realIssue.get());
        }

        log.info("Updating review link comment to issue " + realIssue.get().id());
        PullRequestUtils.postPullRequestLinkComment(realIssue.get(), pr);
    }

    private void addReviewLink(PullRequest pr, IssueTrackerIssue realIssue) {
        var linkBuilder = Link.create(pr.webUrl(), "Review(" + pr.targetRef() + ")")
                .summary(pr.repository().name() + "/" + pr.id());
        if (reviewIcon != null) {
            linkBuilder.iconTitle("Review");
            linkBuilder.iconUrl(reviewIcon);
        }

        log.info("Adding review link to issue " + realIssue.id());
        realIssue.addLink(linkBuilder.build());
    }

    private void removeReviewLink(PullRequest pr, IssueTrackerIssue realIssue) {
        log.info("Removing review links from issue " + realIssue.id());
        var link = Link.create(pr.webUrl(), "").build();
        realIssue.removeLink(link);
    }

    @Override
    public void onNewIssue(PullRequest pr, Path scratchPath, org.openjdk.skara.vcs.openjdk.Issue issue) {
        var realIssue = issueProject.issue(issue.shortId());
        if (realIssue.isEmpty()) {
            log.warning("Pull request " + pr + " added unknown issue: " + issue.id());
            return;
        }

        if (reviewLink) {
            addReviewLink(pr, realIssue.get());
        }

        log.info("Adding review link comment to issue " + realIssue.get().id());
        PullRequestUtils.postPullRequestLinkComment(realIssue.get(), pr);
    }

    @Override
    public void onRemovedIssue(PullRequest pr, Path scratchPath, org.openjdk.skara.vcs.openjdk.Issue issue) {
        var realIssue = issueProject.issue(issue.shortId());
        if (realIssue.isEmpty()) {
            log.warning("Pull request " + pr + " removed unknown issue: " + issue.id());
            return;
        }

        removeReviewLink(pr, realIssue.get());

        PullRequestUtils.removePullRequestLinkComment(realIssue.get(), pr);
    }

    @Override
    public void onNewCommits(HostedRepository repository, Repository localRepository, Path scratchPath, List<Commit> commits, Branch branch) {
        for (var commit : commits) {
            var linkRepository = originalRepository != null ? originalRepository : repository;
            var commitNotification = CommitFormatters.toTextBrief(linkRepository, commit, branch);
            var commitMessage = CommitMessageParsers.v1.parse(commit);
            var username = findIssueUsername(commit, scratchPath);

            for (var commitIssue : commitMessage.issues()) {
                var optionalIssue = issueProject.issue(commitIssue.shortId());
                if (optionalIssue.isEmpty()) {
                    log.severe("Cannot update issue " + commitIssue.id() + " with commit " + commit.hash().abbreviate()
                                       + " - issue not found in issue project");
                    continue;
                }

                var issue = optionalIssue.get();
                var mainIssue = Backports.findMainIssue(issue);
                if (mainIssue.isEmpty()) {
                    log.severe("Issue " + issue.id() + " is not the main issue - bot no corresponding main issue found");
                    continue;
                } else {
                    if (!mainIssue.get().id().equals(issue.id())) {
                        log.warning("Issue " + issue.id() + " is not the main issue - using " + mainIssue.get().id() + " instead");;
                        issue = mainIssue.get();
                    }
                }

                String requestedVersion = null;
                // The actual issue to be updated can change depending on the fix version
                if (setFixVersion) {
                    requestedVersion = getRequestedVersion(localRepository, commit, branch.name());
                    var altFixedVersionIssue = findAltFixedVersionIssue(issue, branch);
                    if (altFixedVersionIssue.isPresent()) {
                        log.info("Found an already fixed backport " + altFixedVersionIssue.get().id() + " for " + issue.id()
                                + " with fixVersion " + Backports.mainFixVersion(altFixedVersionIssue.get()).orElseThrow());
                        issue = altFixedVersionIssue.get();
                        // Do not update fixVersion
                        requestedVersion = null;
                    } else if (requestedVersion != null) {
                        if (!multiFixVersions) {
                            var fixVersion = JdkVersion.parse(requestedVersion).orElseThrow();
                            var existing = Backports.findIssue(issue, fixVersion);
                            if (existing.isEmpty()) {
                                var issueFixVersion = Backports.mainFixVersion(issue);
                                try {
                                    if (issue.isOpen() && avoidForwardports && issueFixVersion.isPresent() &&
                                            fixVersion.compareTo(issueFixVersion.get()) > 0) {
                                        log.info("Avoiding 'forwardport', creating new backport for " + issue.id() +
                                                " with fixVersion " + issueFixVersion.get().raw());
                                        Backports.createBackport(issue, issueFixVersion.get().raw(), username.orElse(null), defaultSecurity(branch));
                                    } else {
                                        log.info("Creating new backport for " + issue.id() + " with fixVersion " +
                                                requestedVersion);
                                        issue =
                                                Backports.createBackport(issue, requestedVersion, username.orElse(null), defaultSecurity(branch));
                                    }
                                } catch (UncheckedRestException e) {
                                    existing = Backports.findIssue(issue, fixVersion);
                                    if (existing.isPresent()) {
                                        log.info(
                                                "Race condition occurred while creating backport issue, returning the existing backport for " +
                                                        issue.id() + " and requested fixVersion "
                                                        + requestedVersion + " " + existing.get().id());
                                        issue = existing.get();
                                    } else {
                                        throw e;
                                    }
                                }
                            } else {
                                log.info("Found existing backport for " + issue.id() + " and requested fixVersion "
                                        + requestedVersion + " " + existing.get().id());
                                issue = existing.get();
                            }
                        }
                    }
                }

                var existingComments = issue.comments();
                var hashUrl = linkRepository.webUrl(commit.hash()).toString();
                // We used to store URLs with just the abbreviated hash, so need to check for both
                var shortHashUrl = linkRepository.webUrl(new Hash(commit.hash().abbreviate())).toString();
                var alreadyPostedComment = existingComments.stream()
                        .filter(comment -> comment.author().equals(issueProject.issueTracker().currentUser()))
                        .anyMatch(comment -> comment.body().contains(hashUrl) || comment.body().contains(shortHashUrl));
                if (!alreadyPostedComment) {
                    issue.addComment(commitNotification);
                }
                log.info("Resolving issue " + issue.id() + " from state " + issue.state());
                // If the issue here was found by findAltFixedVersionIssue(), issue.isFixed() should return true,
                // so issue notifier won't do anything to the issue except posting a comment
                if (!issue.isFixed()) {
                    issue.setState(Issue.State.RESOLVED);
                } else {
                    log.info("The issue was already resolved");
                }
                if (issue.assignees().isEmpty()) {
                    if (username.isPresent()) {
                        setAssigneeForIssue(issue, username.get());
                    }
                }

                if (setFixVersion) {
                    if (requestedVersion != null) {
                        if (buildName != null) {
                            // Check if the build name should be updated
                            var oldBuild = issue.properties().getOrDefault(RESOLVED_IN_BUILD, JSON.of());
                            if (BuildCompare.shouldReplace(buildName, oldBuild.asString())) {
                                log.info("Setting resolved in build for " + issue.id() + " to " + buildName);
                                issue.setProperty(RESOLVED_IN_BUILD, JSON.of(buildName));
                            } else {
                                log.info("Not replacing build " + oldBuild.asString() + " with " + buildName + " for issue " + issue.id());
                            }
                        }
                        if (multiFixVersions) {
                            var currentFixVersions = Backports.fixVersions(issue);
                            log.info("Adding fixVersion " + requestedVersion + " to " + issue.id() + " current: " + currentFixVersions);
                            var jsonFixVersions = JSON.array();
                            currentFixVersions.forEach(jsonFixVersions::add);
                            jsonFixVersions.add(requestedVersion);
                            issue.setProperty("fixVersions", jsonFixVersions);
                        } else {
                            log.info("Setting fixVersion for " + issue.id() + " to " + requestedVersion);
                            issue.setProperty("fixVersions", JSON.array().add(requestedVersion));
                        }
                    }
                }
            }
        }
    }

    private Optional<IssueTrackerIssue> findAltFixedVersionIssue(IssueTrackerIssue issue, Branch branch) {
        if (altFixVersions != null) {
            var matchingBranchPattern = altFixVersions.keySet().stream()
                    .filter(pattern -> pattern.matcher(branch.toString()).matches())
                    .findFirst();
            return matchingBranchPattern.flatMap(branchPattern -> altFixVersions.get(branchPattern).stream()
                    .map(versionPattern -> Backports.findFixedIssue(issue, versionPattern))
                    .flatMap(Optional::stream)
                    .findFirst());
        }
        return Optional.empty();
    }

    private String defaultSecurity(Branch branch) {
        return defaultSecurity.stream()
                .filter(branchSecurity -> branchSecurity.branch.matcher(branch.name()).matches())
                .map(BranchSecurity::securityId)
                .findFirst()
                .orElse(null);
    }

    @Override
    public void onNewOpenJDKTagCommits(HostedRepository repository, Repository localRepository, Path scratchPath, List<Commit> commits, OpenJDKTag tag, Tag.Annotated annotated) throws NonRetriableException {
        if (!setFixVersion) {
            return;
        }
        if (buildName == null) {
            return;
        }
        if (tag.buildNum().isEmpty()) {
            return;
        }

        // Determine which branch(es) this tag belongs to
        var tagBranches = new ArrayList<String>();
        try {
            for (var branch : repository.branches()) {
                if (PreIntegrations.isPreintegrationBranch(branch.name())) {
                    continue;
                }
                var hash = localRepository.resolve(tag.tag()).orElseThrow();
                if (localRepository.isAncestor(hash, branch.hash())) {
                    tagBranches.add(branch.name());
                }
            }
            if (tagBranches.isEmpty()) {
                throw new RuntimeException("Cannot find any branch containing the tag " + tag.tag().name());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        for (var commit : commits) {
            var commitMessage = CommitMessageParsers.v1.parse(commit);
            for (var commitIssue : commitMessage.issues()) {
                var optionalIssue = issueProject.issue(commitIssue.shortId());
                if (optionalIssue.isEmpty()) {
                    log.severe("Cannot update \"Resolved in Build\" for issue " + commitIssue.id()
                                       + " - issue not found in issue project");
                    continue;
                }
                // The actual issue to be updated can change depending on the fix version
                for (var tagBranch : tagBranches) {
                    var issue = optionalIssue.get();
                    String requestedVersion = getRequestedVersion(localRepository, commit, tagBranch);
                    if (requestedVersion == null) {
                        log.info("Cannot update \"Resolved In Build\" for issue: " + issue.id() + ", branch: "
                                + tagBranch + " - no fixVersion configured");
                        continue;
                    }
                    var fixVersion = JdkVersion.parse(requestedVersion).orElseThrow();
                    var existing = Backports.findIssue(issue, fixVersion);
                    if (existing.isEmpty()) {
                        log.info("Cannot update \"Resolved in Build\" for issue: " + issue.id() + ", branch: "
                                + tagBranch + " - no suitable backport found");
                        continue;
                    } else {
                        issue = existing.get();
                    }

                    // Check if the build number should be updated
                    var tagVersion = JdkVersion.parse(tag.version());
                    if (tagVersion.isPresent() && tagVersionMatchesFixVersion(fixVersion, tagVersion.get())) {
                        var oldBuild = issue.properties().getOrDefault(RESOLVED_IN_BUILD, JSON.of());
                        var newBuild = "b" + String.format("%02d", tag.buildNum().get());
                        if (BuildCompare.shouldReplace(newBuild, oldBuild.asString())) {
                            log.info("Setting resolved in build for " + issue.id() + " to " + newBuild);
                            issue.setProperty(RESOLVED_IN_BUILD, JSON.of(newBuild));
                        } else {
                            log.info("Not replacing build " + oldBuild.asString() + " with " + newBuild + " for issue " + issue.id());
                        }
                    } else {
                        log.info("Not updating build in issue " + issue.id() + " with fixVersion " + fixVersion + " from tag " + tag);
                    }
                }
            }
        }
    }

    private boolean tagVersionMatchesFixVersion(JdkVersion fixVersion, JdkVersion tagVersion) {
        // If the fix version has an opt string, check if it should be ignored, otherwise
        // return false if it's not equal.
        if (fixVersion.opt().isPresent() && !tagIgnoreOpt.contains(fixVersion.opt().get())
                && !fixVersion.opt().equals(tagVersion.opt())) {
            return false;
        }
        // At this point, if all the components are equal, we have a match
        if (fixVersion.components().equals(tagVersion.components())) {
            return true;
        }
        // The fixVersion may have a prefix consisting of only lower case letters in the
        // first component that is not present in the tagVersion.
        // e.g. 'openjdk8u342' vs '8u342'
        if (!tagMatchPrefix) {
            var fixComponents = fixVersion.components();
            var tagComponents = tagVersion.components();
            // Check that the rest of the components are equal
            if (fixComponents.size() > 0 && fixComponents.size() == tagComponents.size()
                    && fixComponents.subList(1, fixComponents.size()).equals(tagComponents.subList(1, tagComponents.size()))) {
                var fixFirst = fixComponents.get(0);
                var tagFirst = tagComponents.get(0);
                // Check if the first fixVersion component without the prefix matches
                return fixFirst.matches("[a-z]+" + tagFirst);
            }
        }
        return false;
    }

    private String getRequestedVersion(Repository localRepository, Commit commit, String branch) {
        if (fixVersions != null) {
            var matchingPattern = fixVersions.keySet().stream()
                    .filter(pattern -> pattern.matcher(branch).matches())
                    .findFirst();
            if (matchingPattern.isPresent()) {
                return fixVersions.get(matchingPattern.get());
            }
        }
        try {
            var hash = (useHeadVersion ? localRepository.resolve(branch).orElseThrow() : commit.hash());
            var conf = localRepository.lines(Path.of(".jcheck/conf"), hash);
            if (conf.isPresent()) {
                var parsed = JCheckConfiguration.parse(conf.get());
                var version = parsed.general().version();
                return version.orElse(null);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return null;
    }

    @Override
    public String name() {
        return "issue";
    }

    @Override
    public boolean idempotent() {
        return true;
    }
}
