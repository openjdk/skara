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
package org.openjdk.skara.bots.pr;

import org.openjdk.skara.bot.WorkItem;
import org.openjdk.skara.forge.*;
import org.openjdk.skara.issuetracker.Comment;
import org.openjdk.skara.vcs.Repository;

import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class LabelerWorkItem extends PullRequestWorkItem {
    private static final String initialLabelMessage = "<!-- PullRequestBot initial label help comment -->";

    LabelerWorkItem(PullRequestBot bot, String prId, Consumer<RuntimeException> errorHandler) {
        super(bot, prId, errorHandler);
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

    private void updateLabelMessage(List<Comment> comments, List<String> newLabels) {
        var existing = findComment(comments, initialLabelMessage);
        if (existing.isPresent()) {
            // Only add the comment once per PR
            return;
        }

        var message = new StringBuilder();
        message.append("@");
        message.append(pr.author().username());
        message.append(" ");

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

        message.append("\n");
        message.append(initialLabelMessage);
        pr.addComment(message.toString());
    }

    @Override
    public Collection<WorkItem> prRun(Path scratchPath) {
        if (bot.isAutoLabelled(pr)) {
            return List.of();
        }

        if (bot.labelConfiguration().allowed().isEmpty()) {
            bot.setAutoLabelled(pr);
            return List.of();
        }

        var comments = pr.comments();
        var manuallyAdded = LabelTracker.currentAdded(pr.repository().forge().currentUser(), comments);
        var manuallyRemoved = LabelTracker.currentRemoved(pr.repository().forge().currentUser(), comments);

        // If a manual label command has been issued before we have done any labeling,
        // that is considered to be a request to override any automatic labelling
        if (manuallyAdded.size() > 0 || manuallyRemoved.size() > 0) {
            bot.setAutoLabelled(pr);
            return List.of();
        }

        // If the PR already has one of the allowed labels, that is also considered to override automatic labelling
        var existingAllowed = new HashSet<>(pr.labelNames());
        existingAllowed.retainAll(bot.labelConfiguration().allowed());
        if (!existingAllowed.isEmpty()) {
            bot.setAutoLabelled(pr);
            return List.of();
        }

        try {
            var path = scratchPath.resolve("pr").resolve("labeler").resolve(pr.repository().name());
            var seedPath = bot.seedStorage().orElse(scratchPath.resolve("seeds"));
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
            updateLabelMessage(comments, labelsToAdd);
            labelsToAdd.forEach(pr::addLabel);

            // Remove set labels no longer present unless it has been manually added
            currentLabels.stream()
                         .filter(label -> !newLabels.contains(label))
                         .filter(label -> !manuallyAdded.contains(label))
                         .forEach(pr::removeLabel);
            bot.setAutoLabelled(pr);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return List.of();
    }

    @Override
    public String workItemName() {
        return "labeler";
    }
}
