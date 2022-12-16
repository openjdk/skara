/*
 * Copyright (c) 2020, 2022, Oracle and/or its affiliates. All rights reserved.
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

import org.openjdk.skara.forge.*;
import org.openjdk.skara.issuetracker.Comment;

import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class LabelCommand implements CommandHandler {
    private final String commandName;

    private static final Pattern argumentPattern = Pattern.compile("(?:(add|remove)\\s+)((?:[A-Za-z0-9_@.-]+[\\s,]*)+)");
    private static final Pattern shortArgumentPattern = Pattern.compile("((?:[-+]?[A-Za-z0-9_@.-]+[\\s,]*)+)");
    private static final Pattern ignoredSuffixes = Pattern.compile("^(.*)(?:-dev(?:@openjdk.org)?)$");

    LabelCommand() {
        this("label");
    }

    LabelCommand(String commandName) {
        this.commandName = commandName;
    }

    private void showHelp(LabelConfiguration labelConfiguration, PrintWriter reply) {
        reply.println("Usage: `/" + commandName + " <add|remove> [label[, label, ...]]` " +
                      "or `/" + commandName + " [<+|->label[, <+|->label, ...]]` " +
                      "where `label` is an additional classification that should " +
                      "be applied to this PR. These labels are valid:");
        labelConfiguration.allowed().forEach(label -> reply.println(" * `" + label + "`"));
    }

    @Override
    public void handle(PullRequestBot bot, PullRequest pr, CensusInstance censusInstance, Path scratchPath, CommandInvocation command, List<Comment> allComments, PrintWriter reply) {
        if (!command.user().equals(pr.author()) && (!censusInstance.isCommitter(command.user()))) {
            reply.println("Only the PR author and project [Committers](https://openjdk.org/bylaws#committer) are allowed to modify labels on a PR.");
            return;
        }

        var argumentMatcher = argumentPattern.matcher(command.args());
        var shortArgumentMatcher = shortArgumentPattern.matcher(command.args());
        if (!argumentMatcher.matches() && !shortArgumentMatcher.matches()) {
            showHelp(bot.labelConfiguration(), reply);
            return;
        }
        var currentLabels = new HashSet<>(pr.labelNames());

        if (argumentMatcher.matches()) {
            var labels =  Arrays.stream(argumentMatcher.group(2).split("[\\s,]+")).collect(Collectors.toList());
            if (labels.size() == 0) {
                showHelp(bot.labelConfiguration(), reply);
                return;
            }
            var invalidLabels = verifyLabels(labels, bot);
            if (!invalidLabels.isEmpty()) {
                printInvalidLabels(invalidLabels, bot, reply);
                return;
            }
            if (argumentMatcher.group(1).equals("add")) {
                addLabels(labels, currentLabels, pr, reply);
            } else if (argumentMatcher.group(1).equals("remove")) {
                removeLabels(labels, currentLabels, pr, reply);
            }
            return;
        }

        if (shortArgumentMatcher.matches()) {
            var labels = Arrays.stream(shortArgumentMatcher.group(1).split("[\\s,]+")).collect(Collectors.toList());
            if (labels.size() == 0 || "add".equals(labels.get(0)) || "remove".equals(labels.get(0))) {
                // The comparison of the `add and `remove` is to solve this situation: `/label add +label1, -label2`.
                showHelp(bot.labelConfiguration(), reply);
                return;
            }
            var labelsToAdd = new ArrayList<String>();
            var labelsToRemove = new ArrayList<String>();
            labels.forEach(label -> {
                if (label.startsWith("-")) {
                    labelsToRemove.add(label.substring(1).strip());
                } else if (label.startsWith("+")){
                    labelsToAdd.add(label.substring(1).strip());
                } else {
                    labelsToAdd.add(label.strip());
                }
            });

            var invalidLabels = verifyLabels(labelsToAdd, bot);
            invalidLabels.addAll(verifyLabels(labelsToRemove, bot));
            if (!invalidLabels.isEmpty()) {
                printInvalidLabels(invalidLabels, bot, reply);
                return;
            }

            addLabels(labelsToAdd, currentLabels, pr, reply);
            removeLabels(labelsToRemove, currentLabels, pr, reply);
        }
    }

    private void printInvalidLabels(List<String> invalidLabels, PullRequestBot bot, PrintWriter reply) {
        reply.println(""); // Intentionally blank line.
        invalidLabels.forEach(label -> reply.println("The label `" + label + "` is not a valid label."));
        reply.println("These labels are valid:");
        bot.labelConfiguration().allowed().forEach(l -> reply.println(" * `" + l + "`"));
    }

    /**
     * Verify whether the labels are valid, return the invalid labels.
     */
    private List<String> verifyLabels(List<String> labels, PullRequestBot bot) {
        List<String> invalidLabels = new ArrayList<>();
        for (int i = 0; i < labels.size(); ++i) {
            var label = labels.get(i);
            var ignoredSuffixMatcher = ignoredSuffixes.matcher(label);
            if (ignoredSuffixMatcher.matches()) {
                label = ignoredSuffixMatcher.group(1);
                labels.set(i, label);
            }
            if (!bot.labelConfiguration().allowed().contains(label)) {
                invalidLabels.add(label);
            }
        }
        return invalidLabels;
    }

    private void addLabels(List<String> labelsToAdd,Set<String> currentLabels, PullRequest pr, PrintWriter reply) {
        for (var label : labelsToAdd) {
            if (!currentLabels.contains(label)) {
                pr.addLabel(label);
                reply.println(LabelTracker.addLabelMarker(label));
                reply.println("The `" + label + "` label was successfully added.");
            } else {
                reply.println("The `" + label + "` label was already applied.");
            }
        }
    }

    private void removeLabels(List<String> labelsToRemove,Set<String> currentLabels, PullRequest pr, PrintWriter reply) {
        for (var label : labelsToRemove) {
            if (currentLabels.contains(label)) {
                pr.removeLabel(label);
                reply.println(LabelTracker.removeLabelMarker(label));
                reply.println("The `" + label + "` label was successfully removed.");
            } else {
                reply.println("The `" + label + "` label was not set.");
            }
        }
    }

    @Override
    public String description() {
        return "add or remove an additional classification label";
    }

    @Override
    public boolean allowedInBody() {
        return true;
    }
}
