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
package org.openjdk.skara.bots.notify.issue;

import org.openjdk.skara.bots.notify.*;
import org.openjdk.skara.email.EmailAddress;
import org.openjdk.skara.forge.*;
import org.openjdk.skara.issuetracker.*;
import org.openjdk.skara.issuetracker.Issue;
import org.openjdk.skara.jcheck.JCheckConfiguration;
import org.openjdk.skara.json.JSON;
import org.openjdk.skara.vcs.*;
import org.openjdk.skara.vcs.openjdk.*;

import java.io.*;
import java.net.URI;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;

class IssueNotifier implements Notifier, PullRequestListener, RepositoryListener {
    private final IssueProject issueProject;
    private final boolean reviewLink;
    private final URI reviewIcon;
    private final boolean commitLink;
    private final URI commitIcon;
    private final boolean setFixVersion;
    private final Map<String, String> fixVersions;
    private final JbsBackport jbsBackport;
    private final boolean prOnly;
    private final String buildName;

    private final Logger log = Logger.getLogger("org.openjdk.skara.bots.notify");

    IssueNotifier(IssueProject issueProject, boolean reviewLink, URI reviewIcon, boolean commitLink, URI commitIcon,
            boolean setFixVersion, Map<String, String> fixVersions, JbsBackport jbsBackport, boolean prOnly,
                  String buildName) {
        this.issueProject = issueProject;
        this.reviewLink = reviewLink;
        this.reviewIcon = reviewIcon;
        this.commitLink = commitLink;
        this.commitIcon = commitIcon;
        this.setFixVersion = setFixVersion;
        this.fixVersions = fixVersions;
        this.jbsBackport = jbsBackport;
        this.prOnly = prOnly;
        this.buildName = buildName;
    }

    static IssueNotifierBuilder newBuilder() {
        return new IssueNotifierBuilder();
    }

    private Optional<String> findIssueUsername(Commit commit) {
        var authorEmail = EmailAddress.from(commit.author().email());
        if (authorEmail.domain().equals("openjdk.org")) {
            return Optional.of(authorEmail.localPart());
        } else {
            var user = issueProject.findUser(authorEmail.address());
            if (user.isPresent()) {
                return Optional.of(user.get().username());
            }
        }

        var committerEmail = EmailAddress.from(commit.committer().email());
        if (committerEmail.domain().equals("openjdk.org")) {
            return Optional.of(committerEmail.localPart());
        } else {
            var user = issueProject.findUser(committerEmail.address());
            if (user.isPresent()) {
                return Optional.of(user.get().username());
            }

            log.severe("Cannot determine issue tracker user name from committer email: " + committerEmail);
            return Optional.empty();
        }
    }

    @Override
    public void attachTo(Emitter e) {
        e.registerPullRequestListener(this);
        if (!prOnly || buildName != null) {
            e.registerRepositoryListener(this);
        }
    }

    @Override
    public void onIntegratedPullRequest(PullRequest pr, Hash hash)  {
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
                var linkBuilder = Link.create(repository.webUrl(hash), "Commit")
                                      .summary(repository.name() + "/" + hash.abbreviate());
                if (commitIcon != null) {
                    linkBuilder.iconTitle("Commit");
                    linkBuilder.iconUrl(commitIcon);
                }
                issue.addLink(linkBuilder.build());
            }

            // If prOnly is false, this is instead done when processing commits
            if (prOnly) {
                if (issue.state() == Issue.State.OPEN) {
                    issue.setState(Issue.State.RESOLVED);
                    if (issue.assignees().isEmpty()) {
                        var username = findIssueUsername(commit);
                        if (username.isPresent()) {
                            var assignee = issueProject.issueTracker().user(username.get());
                            assignee.ifPresent(hostUser -> issue.setAssignees(List.of(hostUser)));
                        }
                    }
                }
            }
        }
    }

    @Override
    public void onNewIssue(PullRequest pr, org.openjdk.skara.vcs.openjdk.Issue issue) {
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
    public void onRemovedIssue(PullRequest pr, org.openjdk.skara.vcs.openjdk.Issue issue) {
        var realIssue = issueProject.issue(issue.shortId());
        if (realIssue.isEmpty()) {
            log.warning("Pull request " + pr + " removed unknown issue: " + issue.id());
            return;
        }

        var link = Link.create(pr.webUrl(), "").build();
        realIssue.get().removeLink(link);
    }

    @Override
    public void onNewCommits(HostedRepository repository, Repository localRepository, List<Commit> commits, Branch branch) {
        for (var commit : commits) {
            var commitNotification = CommitFormatters.toTextBrief(repository, commit);
            var commitMessage = CommitMessageParsers.v1.parse(commit);
            var username = findIssueUsername(commit);

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
                        var fixVersion = JdkVersion.parse(requestedVersion);
                        var existing = Backports.findIssue(issue, fixVersion);
                        if (existing.isEmpty()) {
                            issue = jbsBackport.createBackport(issue, requestedVersion, username.orElse(null));
                        } else {
                            issue = existing.get();
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
                        if (username.isPresent()) {
                            var assignee = issueProject.issueTracker().user(username.get());
                            if (assignee.isPresent()) {
                                issue.setAssignees(List.of(assignee.get()));
                            }
                        }
                    }
                }

                if (setFixVersion) {
                    if (requestedVersion != null) {
                        if (buildName != null) {
                            // Check if the build name should be updated
                            var oldBuild = issue.properties().getOrDefault("customfield_10006", JSON.of());
                            if (BuildCompare.shouldReplace(buildName, oldBuild.asString())) {
                                issue.setProperty("customfield_10006", JSON.of(buildName));
                            } else {
                                log.info("Not replacing build " + oldBuild.asString() + " with " + buildName + " for issue " + issue.id());
                            }
                        }
                        issue.setProperty("fixVersions", JSON.of(requestedVersion));
                        Backports.labelReleaseStreamDuplicates(issue, "hgupdate-sync");
                    }
                }
            }
        }
    }

    @Override
    public void onNewOpenJDKTagCommits(HostedRepository repository, Repository localRepository, List<Commit> commits, OpenJDKTag tag, Tag.Annotated annotated) throws NonRetriableException {
        if (!setFixVersion) {
            return;
        }
        if (buildName == null) {
            return;
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
                var issue = optionalIssue.get();

                // Determine which branch this tag belongs to
                String tagBranch = null;
                try {
                    for (var branch : repository.branches()) {
                        if (PreIntegrations.isPreintegrationBranch(branch.name())) {
                            continue;
                        }
                        var hash = localRepository.resolve(tag.tag()).orElseThrow();
                        if (localRepository.isAncestor(hash, branch.hash())) {
                            if (tagBranch == null) {
                                tagBranch = branch.name();
                            } else {
                                throw new RuntimeException("Tag " + tag.tag().name() + " found in both " + tagBranch + " and " + branch.name());
                            }
                        }
                    }
                    if (tagBranch == null) {
                        throw new RuntimeException("Cannot find any branch containing the tag " + tag.tag().name());
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }

                // The actual issue to be updated can change depending on the fix version
                var requestedVersion = fixVersions != null ? fixVersions.getOrDefault(tagBranch, null) : null;
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
                if (requestedVersion == null) {
                    throw new RuntimeException("Failed to determine requested fixVersion for " + issue.id());
                }
                var fixVersion = JdkVersion.parse(requestedVersion);
                var existing = Backports.findIssue(issue, fixVersion);
                if (existing.isEmpty()) {
                    throw new RuntimeException("Cannot find a properly resolved issue for: " + issue.id());
                } else {
                    issue = existing.get();
                }

                // Check if the build name should be updated
                var oldBuild = issue.properties().getOrDefault("customfield_10006", JSON.of());
                var newBuild = "b" + tag.buildNum();
                if (BuildCompare.shouldReplace(newBuild, oldBuild.asString())) {
                    issue.setProperty("customfield_10006", JSON.of(newBuild));
                } else {
                    log.info("Not replacing build " + oldBuild.asString() + " with " + newBuild + " for issue " + issue.id());
                }
            }
        }
    }

    @Override
    public String name() {
        return "issue";
    }
}
