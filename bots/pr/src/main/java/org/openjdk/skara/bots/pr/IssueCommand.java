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

import org.openjdk.skara.forge.PullRequest;
import org.openjdk.skara.issuetracker.Comment;
import org.openjdk.skara.vcs.openjdk.Issue;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

class InvalidIssue extends Exception {
    private String identifier;
    private String reason;

    InvalidIssue(String identifier, String reason) {
        this.identifier = identifier;
        this.reason = reason;
    }

    String identifier() {
        return identifier;
    }

    String reason() {
        return reason;
    }
}

public class IssueCommand implements CommandHandler {
    private final String name;

    private void showHelp(PrintWriter reply) {
        reply.println("Command syntax: `/" + name + " [add|remove] <id>[,<id>,...]` or `/issue [add] <id>: <description>`. For example:");
        reply.println();
        reply.println(" * `/" + name + " add JDK-1234567,4567890`");
        reply.println(" * `/" + name + " remove JDK-4567890`");
        reply.println(" * `/" + name + " 1234567: Use this exact title`");
        reply.println();
        reply.print("If issues are specified only by their ID, the title will be automatically retrieved from JBS. ");
        reply.print("The project prefix (`JDK-` in the above examples) is optional. ");
        reply.println("Separate multiple issue IDs using either spaces or commas.");
    }

    private static final Pattern shortIssuePattern = Pattern.compile("((?:[A-Za-z]+-)?[0-9]+)(?:,| |$)");

    private List<Issue> parseIssueList(String allowedPrefix, String issueList) throws InvalidIssue {
        List<Issue> ret;
        // Is this a single fully described issue?
        var singleIssue = Issue.fromString(issueList);
        if (singleIssue.isPresent()) {
            ret = List.of(singleIssue.get());
        } else {
            var shortIssueMatcher = shortIssuePattern.matcher(issueList);
            ret = shortIssueMatcher.results()
                                   .map(matchResult -> matchResult.group(1))
                                   .map(identifier -> new Issue(identifier, null))
                                   .collect(Collectors.toList());
        }
        for (var issue : ret) {
            if (issue.id().contains("-") && !issue.id().startsWith(allowedPrefix)) {
                throw new InvalidIssue(issue.id(), "This PR can only solve issues in the " + allowedPrefix + " project");
            }
        }

        // Drop the validated project prefixes
        return ret.stream()
                  .map(issue -> issue.id().contains("-") ? new Issue(issue.id().split("-", 2)[1], issue.description()) : issue)
                  .collect(Collectors.toList());
    }

    private final static Pattern subCommandPattern = Pattern.compile("^(add|remove|delete|(?:[A-Za-z]+-)?[0-9]+:?)[ ,]+.*$");

    IssueCommand(String name) {
        this.name = name;
    }

    IssueCommand() {
        this("issue");
    }

    @Override
    public void handle(PullRequestBot bot, PullRequest pr, CensusInstance censusInstance, Path scratchPath, String args, Comment comment, List<Comment> allComments, PrintWriter reply) {
        if (!comment.author().equals(pr.author())) {
            reply.println("Only the author (@" + pr.author().userName() + ") is allowed to issue the `solves` command.");
            return;
        }
        if (args.isBlank()) {
            showHelp(reply);
            return;
        }
        var subCommandMatcher = subCommandPattern.matcher(args);
        if (!subCommandMatcher.matches()) {
            showHelp(reply);
            return;
        }

        var currentSolved = SolvesTracker.currentSolved(pr.repository().forge().currentUser(), allComments)
                                         .stream()
                                         .map(Issue::id)
                                         .collect(Collectors.toSet());
        try {
            if (args.startsWith("remove") || args.startsWith("delete")) {
                var issueListStart = args.indexOf(' ');
                if (issueListStart == -1) {
                    showHelp(reply);
                    return;
                }
                if (currentSolved.isEmpty()) {
                    reply.println("This PR does not contain any additional solved issues that can be removed. To remove the primary solved issue, simply edit the title of this PR.");
                    return;
                }
                var issuesToRemove = parseIssueList(bot.issueProject() == null ? "" : bot.issueProject().name(), args.substring(issueListStart));
                for (var issue : issuesToRemove) {
                    if (currentSolved.contains(issue.id())) {
                        reply.println(SolvesTracker.removeSolvesMarker(issue));
                        reply.println("Removing additional issue from solves list: `" + issue.id() + "`.");
                    } else {
                        reply.print("The issue `" + issue.id() + "` was not found in the list of additional solved issues. The list currently contains these issues: ");
                        var currentList = currentSolved.stream()
                                                       .map(id -> "`" + id + "`")
                                                       .collect(Collectors.joining(", "));
                        reply.println(currentList);
                    }
                }
            } else {
                if (args.startsWith("add")) {
                    var issueListStart = args.indexOf(' ');
                    if (issueListStart == -1) {
                        showHelp(reply);
                        return;
                    }
                    args = args.substring(issueListStart);
                }
                var issues = parseIssueList(bot.issueProject() == null ? "" : bot.issueProject().name(), args);
                if (issues.size() == 0) {
                    showHelp(reply);
                    return;
                }
                var validatedIssues = new ArrayList<Issue>();
                for (var issue : issues) {
                    try {
                        if (bot.issueProject() == null) {
                            if (issue.description() == null) {
                                reply.print("This repository does not have an issue project configured - you will need to input the issue title manually ");
                                reply.println("using the syntax `/solves " + issue.id() + ": title of the issue`.");
                                return;
                            } else {
                                validatedIssues.add(issue);
                                continue;
                            }
                        }
                        var validatedIssue = bot.issueProject().issue(issue.id());
                        if (validatedIssue.isEmpty()) {
                            reply.println("The issue `" + issue.id() + "` was not found in the `" + bot.issueProject().name() + "` project - make sure you have entered it correctly.");
                            continue;
                        }
                        if (validatedIssue.get().state() != org.openjdk.skara.issuetracker.Issue.State.OPEN) {
                            reply.println("The issue [" + validatedIssue.get().id() + "](" + validatedIssue.get().webUrl() + ") isn't open - make sure you have selected the correct issue.");
                            continue;
                        }
                        if (issue.description() == null) {
                            validatedIssues.add(new Issue(validatedIssue.get().id(), validatedIssue.get().title()));
                        } else {
                            validatedIssues.add(new Issue(validatedIssue.get().id(), issue.description()));
                        }

                    } catch (RuntimeException e) {
                        if (issue.description() == null) {
                            reply.print("Temporary failure when trying to look up issue `" + issue.id() + "` - you will need to input the issue title manually ");
                            reply.println("using the syntax `/solves " + issue.id() + ": title of the issue`.");
                            return;
                        } else {
                            validatedIssues.add(issue);
                        }
                    }
                }
                if (validatedIssues.size() != issues.size()) {
                    reply.println("As there were validation problems, no additional issues will be added to the list of solved issues.");
                    return;
                }

                // Drop the validated project prefixes
                var strippedValidatedIssues = validatedIssues.stream()
                                                             .map(issue -> issue.id().contains("-") ? new Issue(issue.id().split("-", 2)[1], issue.description()) : issue)
                                                             .collect(Collectors.toList());
                var titleIssue = Issue.fromString(pr.title());
                for (var issue : strippedValidatedIssues) {
                    if (titleIssue.isEmpty()) {
                        reply.print("The primary solved issue for a PR is set through the PR title. Since the current title does ");
                        reply.println("not contain an issue reference, it will now be updated.");
                        pr.setTitle(issue.toString());
                        titleIssue = Optional.of(issue);
                        continue;
                    }
                    if (titleIssue.get().id().equals(issue.id())) {
                        reply.println("This issue is referenced in the PR title - it will now be updated.");
                        pr.setTitle(issue.toString());
                        continue;
                    }
                    reply.println(SolvesTracker.setSolvesMarker(issue));
                    if (currentSolved.contains(issue.id())) {
                        reply.println("Updating description of additional solved issue: `" + issue.toString() + "`.");
                    } else {
                        reply.println("Adding additional issue to solves list: `" + issue.toString() + "`.");
                    }
                }
            }

        } catch (InvalidIssue invalidIssue) {
            reply.println("The issue identifier `" + invalidIssue.identifier() + "` is invalid: " + invalidIssue.reason() + ".");
        }
    }

    @Override
    public String description() {
        return "edit the list of issues that this PR solves";
    }
}
