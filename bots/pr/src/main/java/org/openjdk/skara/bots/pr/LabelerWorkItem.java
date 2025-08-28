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
package org.openjdk.skara.bots.pr;

import java.time.ZonedDateTime;
import org.openjdk.skara.bot.WorkItem;
import org.openjdk.skara.forge.*;
import org.openjdk.skara.issuetracker.Comment;
import org.openjdk.skara.vcs.Hash;
import org.openjdk.skara.vcs.Repository;

import java.io.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class LabelerWorkItem extends PullRequestWorkItem {
    protected static final String INITIAL_LABEL_MESSAGE = "<!-- PullRequestBot initial label help comment -->";
    private static final String LABEL_COMMIT_MARKER = "<!-- PullRequest Bot label commit '%s' -->";
    protected static final Pattern LABEL_COMMIT_PATTERN = Pattern.compile("<!-- PullRequest Bot label commit '(.*?)' -->");
    private static final Logger log = Logger.getLogger("org.openjdk.skara.bots.pr");

    LabelerWorkItem(PullRequestBot bot, String prId, Consumer<RuntimeException> errorHandler,
            ZonedDateTime prUpdatedAt) {
        super(bot, prId, errorHandler, prUpdatedAt, false);
    }

    @Override
    public String toString() {
        return "LabelerWorkItem@" + bot.repo().name() + "#" + prId;
    }

    private Set<String> getLabels(Repository localRepo) throws IOException {
        var files = PullRequestUtils.changedFiles(pr, localRepo);
        return bot.labelConfiguration().label(files);
    }

    private Optional<Comment> findComment(List<Comment> comments, String marker) {
        var self = pr.repository().forge().currentUser();
        return comments.stream()
                       .filter(comment -> comment.author().equals(self))
                       .filter(comment -> comment.body().contains(marker))
                       .findAny();
    }

    private void updateLabelMessage(List<Comment> comments, List<String> newLabels, String commitHash, boolean autoLabeled) {
        var existing = findComment(comments, INITIAL_LABEL_MESSAGE);
        if (existing.isPresent()) {
            // Only add the comment once per PR
            return;
        }

        var message = new StringBuilder();
        message.append("@");
        message.append(pr.author().username());
        message.append(" ");

        if (autoLabeled) {
            if (newLabels.isEmpty()) {
                message.append("To determine the appropriate audience for reviewing this pull request, one or more ");
                message.append("labels corresponding to different subsystems will normally be applied automatically. ");
                message.append("However, no automatic labelling rule matches the changes in this pull request. ");
                message.append("In order to have an \"RFR\" email sent to the correct mailing list, you will ");
                message.append("need to add one or more applicable labels manually using the ");
                message.append("[/label](https://wiki.openjdk.org/display/SKARA/Pull+Request+Commands#PullRequestCommands-/label)");
                message.append(" pull request command.\n\n");
                message.append("<details>\n");
                message.append("<summary>Applicable Labels</summary>\n");
                message.append("<br>\n");
                message.append("\n");
                bot.labelConfiguration().allowed()
                        .stream()
                        .sorted()
                        .forEach(label -> message.append("- `" + label + "`\n"));
                message.append("\n");
                message.append("</details>");
            } else {
                message.append("The following label");
                if (newLabels.size() > 1) {
                    message.append("s");
                }
                message.append(" will be automatically applied to this pull request:\n\n");
                newLabels.stream()
                        .sorted()
                        .forEach(label -> message.append("- `" + label + "`\n"));
                message.append("\n");
                message.append("When this pull request is ready to be reviewed, an \"RFR\" email will be sent to the ");
                message.append("corresponding mailing list");
                if (newLabels.size() > 1) {
                    message.append("s");
                }
                message.append(". If you would like to change these labels, use the ");
                message.append("[/label](https://wiki.openjdk.org/display/SKARA/Pull+Request+Commands#PullRequestCommands-/label)");
                message.append(" pull request command.");
            }
        } else {
            message.append("A manual label command was issued before auto-labeling, so auto-labeling was skipped.");
        }

        message.append("\n");
        message.append(INITIAL_LABEL_MESSAGE);
        message.append("\n");
        message.append(String.format(LABEL_COMMIT_MARKER, commitHash));
        pr.addComment(message.toString());
    }

    @Override
    public Collection<WorkItem> prRun(ScratchArea scratchArea) {
        // If no label configuration, return early
        if (bot.labelConfiguration().allowed().isEmpty()) {
            bot.setAutoLabelled(pr);
            return List.of();
        }

        // Updating labels when new files are touched
        if (bot.isAutoLabelled(pr)) {
            try {
                var oldLabels = new HashSet<>(pr.labelNames());
                var newLabels = new HashSet<>(pr.labelNames());

                var path = scratchArea.get(pr.repository());
                var seedPath = bot.seedStorage().orElse(scratchArea.getSeeds());
                var hostedRepositoryPool = new HostedRepositoryPool(seedPath);
                var localRepo = PullRequestUtils.materialize(hostedRepositoryPool, pr, path);

                var labelComment = findComment(prComments(), INITIAL_LABEL_MESSAGE);

                if (labelComment.isPresent()) {
                    var line = labelComment.get().body().lines()
                            .map(LABEL_COMMIT_PATTERN::matcher)
                            .filter(Matcher::find)
                            .findFirst();

                    if (line.isPresent()) {
                        var evaluatedCommitHash = line.get().group(1);
                        var changedFiles = PullRequestUtils.changedFiles(pr, localRepo, new Hash(evaluatedCommitHash));
                        var newLabelsNeedToBeAdded = bot.labelConfiguration().label(changedFiles);
                        newLabels.addAll(newLabelsNeedToBeAdded);

                        var upgradedLabels = bot.labelConfiguration().upgradeLabelsToGroups(newLabels);
                        newLabels.addAll(upgradedLabels);
                        newLabels.removeIf(label -> !upgradedLabels.contains(label));
                    }

                    for (var newLabel : newLabels) {
                        if (!oldLabels.contains(newLabel)) {
                            log.info("Adding label " + newLabel);
                            pr.addLabel(newLabel);
                        }
                    }

                    for (var oldLabel : oldLabels) {
                        if (!newLabels.contains(oldLabel)) {
                            log.info("Removing label " + oldLabel);
                            pr.removeLabel(oldLabel);
                        }
                    }

                    pr.updateComment(labelComment.get().id(), labelComment.get().body().replaceAll(
                            "(<!-- PullRequest Bot label commit ')[^']*(' -->)",
                            "$1" + pr.headHash().toString() + "$2"
                    ));
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            // No need to return CheckWorkItem, if there is any label added, in the next round of CheckWorkItem, it will re-evaluate the pr
            return List.of();
        }

        // Initial auto labeling
        var comments = prComments();
        var labelNames = new HashSet<>(pr.labelNames());
        var manuallyAdded = LabelTracker.currentAdded(pr.repository().forge().currentUser(), comments);
        var manuallyRemoved = LabelTracker.currentRemoved(pr.repository().forge().currentUser(), comments);

        // If a manual label command has been issued before we have done any labeling,
        // that is considered to be a request to override any automatic labelling
        if (manuallyAdded.size() > 0 || manuallyRemoved.size() > 0) {
            bot.setAutoLabelled(pr);
            updateLabelMessage(comments, List.of(), pr.headHash().toString(), false);
            return needsRfrCheck(labelNames);
        }

        // If the PR already has one of the allowed labels, that is also considered to override automatic labelling
        var existingAllowed = new HashSet<>(labelNames);
        existingAllowed.retainAll(bot.labelConfiguration().allowed());
        if (!existingAllowed.isEmpty()) {
            bot.setAutoLabelled(pr);
            return needsRfrCheck(labelNames);
        }

        try {
            var path = scratchArea.get(pr.repository());
            var seedPath = bot.seedStorage().orElse(scratchArea.getSeeds());
            var hostedRepositoryPool = new HostedRepositoryPool(seedPath);
            var localRepo = PullRequestUtils.materialize(hostedRepositoryPool, pr, path);
            var newLabels = getLabels(localRepo);
            var currentLabels = pr.labelNames().stream()
                                  .filter(key -> bot.labelConfiguration().allowed().contains(key))
                                  .collect(Collectors.toSet());


            // Add all labels not already set that are not manually removed
            var labelsToAdd = newLabels.stream()
                     .filter(label -> !currentLabels.contains(label))
                     .filter(label -> !manuallyRemoved.contains(label))
                                       .collect(Collectors.toList());
            labelsToAdd.forEach(pr::addLabel);

            // Remove set labels no longer present unless it has been manually added
            currentLabels.stream()
                         .filter(label -> !newLabels.contains(label))
                         .filter(label -> !manuallyAdded.contains(label))
                         .forEach(pr::removeLabel);
            bot.setAutoLabelled(pr);

            updateLabelMessage(comments, labelsToAdd, pr.headHash().toString(), true);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return needsRfrCheck(labelNames);
    }

    private Collection<WorkItem> needsRfrCheck(Set<String> labelNames) {
        if (!labelNames.contains("rfr")) {
            return List.of(CheckWorkItem.fromWorkItemWithForceUpdate(bot, prId, errorHandler, triggerUpdatedAt));
        }
        return List.of();
    }

    @Override
    public String workItemName() {
        return "labeler";
    }
}
