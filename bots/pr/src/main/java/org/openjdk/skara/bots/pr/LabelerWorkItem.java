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

import static org.openjdk.skara.bots.pr.CheckRun.findComment;
import static org.openjdk.skara.bots.pr.CheckRun.syncLabels;

public class LabelerWorkItem extends PullRequestWorkItem {
    protected static final String INITIAL_LABEL_MESSAGE = "<!-- PullRequestBot initial label help comment -->";
    private static final String LABEL_COMMIT_MARKER = "<!-- PullRequest Bot label commit '%s' -->";
    protected static final Pattern LABEL_COMMIT_PATTERN = Pattern.compile("<!-- PullRequest Bot label commit '(.*?)' -->");
    private static final String AUTO_LABEL_ADDITIONAL_COMMENT_MARKER = "<!-- PullRequest Bot auto label additional comment '%s' -->";
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


    private void updateLabelMessage(List<Comment> comments, List<String> newLabels, String commitHash) {
        var existing = findComment(comments, INITIAL_LABEL_MESSAGE, pr);
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
        message.append(INITIAL_LABEL_MESSAGE);
        message.append("\n");
        message.append(String.format(LABEL_COMMIT_MARKER, commitHash));
        pr.addComment(message.toString());
    }

    @Override
    public Collection<WorkItem> prRun(ScratchArea scratchArea) {
        // If no label configuration, return early
        if (bot.labelConfiguration().allowed().isEmpty()) {
            return List.of();
        }

        var comments = prComments();
        var initialLabelComment = findComment(comments, INITIAL_LABEL_MESSAGE, pr);
        Set<String> oldLabels = new HashSet<>(pr.labelNames());
        Set<String> newLabels = new HashSet<>(pr.labelNames());

        // If the initial label comment can be found,Updating labels when new files are touched
        if (initialLabelComment.isPresent()) {
            try {
                var localRepo = IntegrateCommand.materializeLocalRepo(bot, pr, scratchArea);
                var autoLabeledHashOpt = autoLabeledHash(comments, pr);
                if (autoLabeledHashOpt.isPresent()) {
                    var evaluatedCommitHash = autoLabeledHashOpt.get();
                    var changedFiles = PullRequestUtils.changedFiles(pr, localRepo, new Hash(evaluatedCommitHash));
                    var newLabelsNeedToBeAdded = bot.labelConfiguration().label(changedFiles);
                    newLabels.addAll(newLabelsNeedToBeAdded);

                    newLabels = bot.labelConfiguration().upgradeLabelsToGroups(newLabels);

                    syncLabels(pr, oldLabels, newLabels, log);

                    if (!newLabelsNeedToBeAdded.isEmpty()) {
                        addLabelAutoUpdateAdditionalComment(comments, new ArrayList<>(newLabelsNeedToBeAdded), pr.headHash().hex());
                    }

                    pr.updateComment(initialLabelComment.get().id(), initialLabelComment.get().body().replaceAll(
                            "(<!-- PullRequest Bot label commit ')[^']*(' -->)",
                            "$1" + pr.headHash().toString() + "$2"));
                } else {
                    // If auto label comment is present but auto label hash isn't present, mark the headHash as handled.
                    pr.updateComment(initialLabelComment.get().id(),
                            initialLabelComment.get().body() + "\n" + String.format(LABEL_COMMIT_MARKER, pr.headHash().toString()));
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            // No need to return CheckWorkItem, if there is any label added, in the next round of CheckWorkItem, it will re-evaluate the pr
            return List.of();
        }

        // Initial auto labeling
        try {
            var localRepo = IntegrateCommand.materializeLocalRepo(bot, pr, scratchArea);
            var labelsToAdd = getLabels(localRepo);
            newLabels.addAll(labelsToAdd);
            newLabels = bot.labelConfiguration().upgradeLabelsToGroups(newLabels);
            syncLabels(pr, oldLabels, newLabels, log);
            updateLabelMessage(comments, new ArrayList<>(labelsToAdd), pr.headHash().toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return needsRfrCheck(newLabels);
    }

    void addLabelAutoUpdateAdditionalComment(List<Comment> comments, List<String> labelsAdded, String commitHash) {
        if (findComment(comments, String.format(AUTO_LABEL_ADDITIONAL_COMMENT_MARKER, commitHash), pr).isPresent()) {
            // Only add the comment once
            return;
        }
        var message = new StringBuilder();
        message.append("@");
        message.append(pr.author().username());
        message.append(" ");
        if (!labelsAdded.isEmpty()) {
            Collections.sort(labelsAdded);
            message.append(labelsAdded.stream()
                    .map(label -> "`" + label + "`")
                    .collect(Collectors.joining(", ")));
            message.append(labelsAdded.size() == 1 ? " has" : " have");
            message.append(" been added to this pr based on the files touched in your new commit(s).");
        }
        message.append("\n");
        message.append(String.format(AUTO_LABEL_ADDITIONAL_COMMENT_MARKER, commitHash));
        pr.addComment(message.toString());
    }

    static Optional<String> autoLabeledHash(List<Comment> comments, PullRequest pr) {
        var labelComment = findComment(comments, INITIAL_LABEL_MESSAGE, pr);
        if (labelComment.isPresent()) {
            var line = labelComment.get().body().lines()
                    .map(LABEL_COMMIT_PATTERN::matcher)
                    .filter(Matcher::find)
                    .findFirst();
            if (line.isPresent()) {
                return Optional.of(line.get().group(1));
            }
        }
        return Optional.empty();
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
