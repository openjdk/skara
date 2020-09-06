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

    LabelerWorkItem(PullRequestBot bot, PullRequest pr, Consumer<RuntimeException> errorHandler) {
        super(bot, pr, errorHandler);
    }

    @Override
    public String toString() {
        return "LabelerWorkItem@" + pr.repository().name() + "#" + pr.id();
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
        message.append(pr.author().userName());
        message.append(" ");

        if (newLabels.isEmpty()) {
            message.append("To determine the appropriate audience for reviewing this pull request, one or more ");
            message.append("labels corresponding to different subsystems will normally be applied automatically. ");
            message.append("However, no automatic labelling rule matches the changes in this pull request.\n\n");
            message.append("In order to have an RFR email automatically sent to the correct mailing list, you will ");
            message.append("need to add one or more labels manually using the `/label add \"label\"` command. ");
            message.append("The following labels are valid: ");
            var labels = bot.labelConfiguration().allowed().stream()
                            .sorted()
                            .map(label -> "`" + label + "`")
                            .collect(Collectors.joining(" "));
            message.append(labels);
            message.append(".");
        } else {
            message.append("The following label");
            if (newLabels.size() > 1) {
                message.append("s");
            }
            message.append(" will be automatically applied to this pull request: ");
            var labels = newLabels.stream()
                                  .sorted()
                                  .map(label -> "`" + label + "`")
                                  .collect(Collectors.joining(" "));
            message.append(labels);
            message.append(".\n\nWhen this pull request is ready to be reviewed, an RFR email will be sent to the ");
            message.append("corresponding mailing list");
            if (newLabels.size() > 1) {
                message.append("s");
            }
            message.append(". If you would like to change these labels, use the `/label (add|remove) \"label\"` command.");
        }

        message.append("\n");
        message.append(initialLabelMessage);
        pr.addComment(message.toString());
    }

    @Override
    public Collection<WorkItem> run(Path scratchPath) {
        if (bot.currentLabels().containsKey(pr.headHash())) {
            return List.of();
        }
        if (bot.labelConfiguration().allowed().isEmpty()) {
            return List.of();
        }
        try {
            var path = scratchPath.resolve("pr").resolve("labeler").resolve(pr.repository().name());
            var seedPath = bot.seedStorage().orElse(scratchPath.resolve("seeds"));
            var hostedRepositoryPool = new HostedRepositoryPool(seedPath);
            var localRepo = PullRequestUtils.materialize(hostedRepositoryPool, pr, path);
            var newLabels = getLabels(localRepo);
            var currentLabels = pr.labels().stream()
                                  .filter(key -> bot.labelConfiguration().allowed().contains(key))
                                  .collect(Collectors.toSet());

            var comments = pr.comments();
            var manuallyAdded = LabelTracker.currentAdded(pr.repository().forge().currentUser(), comments);
            var manuallyRemoved = LabelTracker.currentRemoved(pr.repository().forge().currentUser(), comments);

            // If a manual label command has been issued before we have done any labeling,
            // that is considered to be a request to override any automatic labelling
            if (bot.currentLabels().isEmpty() && !(manuallyAdded.isEmpty() && manuallyRemoved.isEmpty())) {
                return List.of();
            }

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

            bot.currentLabels().put(pr.headHash(), Boolean.TRUE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return List.of();
    }
}
