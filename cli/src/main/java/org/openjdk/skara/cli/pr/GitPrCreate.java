/*
 * Copyright (c) 2019, 2020, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.cli.pr;

import org.openjdk.skara.args.*;
import org.openjdk.skara.cli.GitPublish;
import org.openjdk.skara.cli.GitJCheck;
import org.openjdk.skara.vcs.Branch;
import org.openjdk.skara.vcs.openjdk.CommitMessageParsers;

import static org.openjdk.skara.cli.pr.Utils.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public class GitPrCreate {
    public static void main(String[] args) throws IOException, InterruptedException {
        var flags = List.of(
            Option.shortcut("u")
                  .fullname("username")
                  .describe("NAME")
                  .helptext("Username on host")
                  .optional(),
            Option.shortcut("r")
                  .fullname("remote")
                  .describe("NAME")
                  .helptext("Name of remote, defaults to 'origin'")
                  .optional(),
            Option.shortcut("b")
                  .fullname("branch")
                  .describe("NAME")
                  .helptext("Name of target branch, defaults to 'master'")
                  .optional(),
            Switch.shortcut("")
                  .fullname("ignore-workspace")
                  .helptext("Ignore local changes in worktree and staging area when creating pull request")
                  .optional(),
            Switch.shortcut("")
                  .fullname("ignore-local-commits")
                  .helptext("Ignore local commits not pushed when creating pull request")
                  .optional(),
            Switch.shortcut("")
                  .fullname("publish")
                  .helptext("Publish the local branch before creating the pull request")
                  .optional(),
            Switch.shortcut("")
                  .fullname("jcheck")
                  .helptext("Run jcheck before creating the pull request")
                  .optional(),
            Switch.shortcut("")
                  .fullname("draft")
                  .helptext("Create a pull request in draft state")
                  .optional(),
            Switch.shortcut("")
                  .fullname("verbose")
                  .helptext("Turn on verbose output")
                  .optional(),
            Switch.shortcut("")
                  .fullname("debug")
                  .helptext("Turn on debugging output")
                  .optional(),
            Switch.shortcut("")
                  .fullname("version")
                  .helptext("Print the version of this tool")
                  .optional()
        );

        var inputs = List.of(
            Input.position(0)
                 .describe("ID")
                 .singular()
                 .optional()
        );

        var parser = new ArgumentParser("git-pr", flags, inputs);
        var arguments = parse(parser, args);
        var repo = getRepo();
        var uri = getURI(repo, arguments);
        var host = getForge(uri, repo, arguments);
        var remote = getRemote(repo, arguments);
        var currentBranch = repo.currentBranch().orElseGet(() -> {
                System.err.println("error: the repository is in a detached HEAD state");
                System.exit(1);
                return null;
        });

        var remoteRepo = host.repository(projectName(uri)).orElseThrow(() ->
                new IOException("Could not find repository at " + uri.toString())
        );
        var parentRepo = remoteRepo.parent().orElseThrow(() ->
                new IOException("error: remote repository " + uri + " is not a fork of any repository")
        );

        var upstreamBranchNames = repo.remoteBranches(parentRepo.webUrl().toString())
                                      .stream()
                                      .map(r -> r.name())
                                      .collect(Collectors.toSet());
        if (upstreamBranchNames.contains(currentBranch.name())) {
            System.err.println("error: you should not create pull requests from a branch present in the upstream repository.");
            System.err.println("");
            System.err.println("To create a local branch for your changes and restore the '" + currentBranch.name() + "' branch, run:");
            System.err.println("");
            System.err.println("    git checkout -b NAME-FOR-YOUR-LOCAL-BRANCH");
            System.err.println("    git branch --force " + currentBranch.name() + " origin/" + currentBranch.name());
            System.err.println("");
            System.exit(1);
        }

        var ignoreWorkspace = getSwitch("ignore-workspace", "create", arguments);
        if (!ignoreWorkspace) {
            var diff = repo.diff(repo.head());
            if (!diff.patches().isEmpty()) {
                System.err.println("error: there are uncommitted changes in your working tree:");
                System.err.println("");
                for (var patch : diff.patches()) {
                    var path = patch.target().path().isPresent() ?
                        patch.target().path().get() : patch.source().path().get();
                    System.err.println("    " + patch.status().toString() + " " + path.toString());
                }
                System.err.println("");
                System.err.println("If these changes are meant to be part of the pull request, run:");
                System.err.println("");
                System.err.println("    git commit -am 'Forgot to add some changes'");
                System.err.println("");
                System.err.println("If these changes are *not* meant to be part of the pull request, run:");
                System.err.println("");
                System.err.println("    git stash");
                System.err.println("");
                System.err.println("(You can later restore the changes by running: git stash pop)");
                System.err.println("");
                System.err.println("If you want to ignore this error, run:");
                System.err.println("");
                System.err.println("     git config --global pr.create.ignore-workspace true");
                System.err.println("");
                System.exit(1);
            }
        }

        var upstream = repo.upstreamFor(currentBranch);
        var shouldPublish = getSwitch("publish", "create", arguments);
        if (upstream.isEmpty() && !shouldPublish) {
            System.err.println("error: there is no remote branch for the local branch '" + currentBranch.name() + "'");
            System.err.println("");
            System.err.println("A remote branch must be present at " + uri + " to create a pull request");
            System.err.println("To create a remote branch and push the commits for your local branch, run:");
            System.err.println("");
            System.err.println("    git publish");
            System.err.println("");
            System.err.println("If you created the remote branch from another client, you must update this repository.");
            System.err.println("To update remote information for this repository, run:");
            System.err.println("");
            System.err.println("    git fetch " + remote);
            System.err.println("    git branch --set-upstream " + currentBranch + " " + remote + "/" + currentBranch);
            System.err.println("");
            System.err.println("If you want 'git pr create' to automatically publish branches, run:");
            System.err.println("");
            System.err.println("    git config --global pr.create.publish true");
            System.err.println("");
            System.exit(1);
        }

        var shouldIgnoreLocalCommits = getSwitch("ignore-local-commits", "create", arguments);
        if (!shouldIgnoreLocalCommits && !shouldPublish) {
            var upstreamRefName = upstream.get().substring(remote.length() + 1);
            repo.fetch(uri, upstreamRefName);

            var branchCommits = repo.commits(upstream.get() + ".." + currentBranch.name()).asList();
            if (!branchCommits.isEmpty()) {
                System.err.println("error: there are local commits on branch '" + currentBranch.name() + "' not present in the remote repository " + uri);
                System.err.println("");
                System.err.println("All commits must be present in the remote repository to be part of the pull request");
                System.err.println("The following commits are not present in the remote repository:");
                System.err.println("");
                for (var commit : branchCommits) {
                    System.err.println("- " + commit.hash().abbreviate() + ": " + commit.message().get(0));
                }
                System.err.println("");
                System.err.println("To push the above local commits to the remote repository, run:");
                System.err.println("");
                System.err.println("    git push " + remote + " " + currentBranch.name());
                System.err.println("");
                System.err.println("If you want to ignore this error, run:");
                System.err.println("");
                System.err.println("     git config --global pr.create.ignore-local-commits true");
                System.err.println("");
                System.exit(1);
            }
        }

        var targetBranch = getOption("branch", "create", arguments);
        if (targetBranch == null) {
            var remoteBranches = repo.branches(remote);
            var candidates = new ArrayList<Branch>();
            for (var b : remoteBranches) {
                var withoutRemotePrefix = b.name().substring(remote.length() + 1);
                if (upstreamBranchNames.contains(withoutRemotePrefix)) {
                    candidates.add(b);
                }
            }

            var localBranches = repo.branches();
            Branch closest = null;
            var shortestDistance = Integer.MAX_VALUE;
            for (var b : candidates) {
                var from = b.name();
                for (var localBranch : localBranches) {
                    var trackingBranch = repo.upstreamFor(localBranch);
                    if (trackingBranch.isPresent() &&
                        trackingBranch.get().equals(b.name())) {
                        from = localBranch.name();
                    }
                }
                var distance = repo.commitMetadata(from + "..." + currentBranch.name()).size();
                if (distance < shortestDistance) {
                    closest = b;
                    shortestDistance = distance;
                }
            }

            if (closest != null) {
                targetBranch = closest.name().substring(remote.length() + 1);
            } else {
                System.err.println("error: cannot automatically infer target branch");
                System.err.println("       use --branch to specify target branch");
                System.exit(1);
            }
        }

        var headRef = upstream.isEmpty() ? currentBranch.name() : upstream.get();
        var commits = repo.commits(targetBranch + ".." + headRef).asList();
        if (commits.isEmpty()) {
            System.err.println("error: no difference between branches " + targetBranch + " and " + headRef);
            System.err.println("       Cannot create an empty pull request, have you committed?");
            System.exit(1);
        }

        var shouldRunJCheck = getSwitch("jcheck", "create", arguments);
        if (shouldRunJCheck) {
            var jcheckArgs = new String[]{ "--ignore=branches,committer,reviewers,issues", "--rev", targetBranch + ".." + headRef };
            var err = GitJCheck.run(repo, jcheckArgs);
            if (err != 0) {
                System.exit(err);
            }
        }

        var project = jbsProjectFromJcheckConf(repo, targetBranch);
        var issue = getIssue(currentBranch, project);
        var file = Files.createTempFile("PULL_REQUEST_", ".md");
        if (issue.isPresent()) {
            Files.writeString(file, format(issue.get()) + "\n\n");
        } else if (commits.size() == 1) {
            var commit = commits.get(0);
            issue = getIssue(commit, project);
            if (issue.isPresent()) {
                Files.writeString(file, format(issue.get()) + "\n\n");
            } else {
                var message = CommitMessageParsers.v1.parse(commit.message());
                Files.writeString(file, message.title() + "\n");
                if (!message.summaries().isEmpty()) {
                    Files.write(file, message.summaries(), StandardOpenOption.APPEND);
                }
                if (!message.additional().isEmpty()) {
                    Files.write(file, message.additional(), StandardOpenOption.APPEND);
                }
            }
        } else {
            Files.write(file, List.of(""));
        }

        appendPaddedHTMLComment(file, "Please enter the pull request message for your changes.");
        appendPaddedHTMLComment(file, "The first line will be considered the subject, use a blank line to");
        appendPaddedHTMLComment(file, "separate the subject from the body. These HTML comment lines will");
        appendPaddedHTMLComment(file, "be removed automatically. An empty message aborts the pull request.");
        appendPaddedHTMLComment(file, "");
        appendPaddedHTMLComment(file, "Commits to be included from branch '" + currentBranch.name() + "':");
        for (var commit : commits) {
            var desc = commit.hash().abbreviate() + ": " + commit.message().get(0);
            appendPaddedHTMLComment(file, "- " + desc);
            if (!commit.isMerge()) {
                var diff = commit.parentDiffs().get(0);
                for (var patch : diff.patches()) {
                    var status = patch.status();
                    if (status.isModified()) {
                        appendPaddedHTMLComment(file, "  M  " + patch.target().path().get().toString());
                    } else if (status.isAdded()) {
                        appendPaddedHTMLComment(file, "  A  " + patch.target().path().get().toString());
                    } else if (status.isDeleted()) {
                        appendPaddedHTMLComment(file, "  D  " + patch.source().path().get().toString());
                    } else if (status.isRenamed()) {
                        appendPaddedHTMLComment(file, "  R  " + patch.target().path().get().toString());
                        appendPaddedHTMLComment(file, "      (" + patch.source().path().get().toString() + ")");
                    } else if (status.isCopied()) {
                        appendPaddedHTMLComment(file, "  C  " + patch.target().path().get().toString());
                        appendPaddedHTMLComment(file, "      (" + patch.source().path().get().toString() + ")");
                    }
                }
            }
        }
        appendPaddedHTMLComment(file, "");
        if (issue.isPresent()) {
            appendPaddedHTMLComment(file, "Issue:      " + issue.get().webUrl());
        }
        appendPaddedHTMLComment(file, "Repository: " + parentRepo.webUrl());
        appendPaddedHTMLComment(file, "Branch:     " + targetBranch);

        var success = spawnEditor(repo, file);
        if (!success) {
            System.err.println("error: editor exited with non-zero status code, aborting");
            System.exit(1);
        }
        var lines = Files.readAllLines(file)
                         .stream()
                         .filter(l -> !(l.startsWith("<!--") && l.endsWith("-->")))
                         .collect(Collectors.toList());
        var isEmpty = lines.stream().allMatch(String::isEmpty);
        if (isEmpty) {
            System.err.println("error: no message present, aborting");
            System.exit(1);
        }

        var title = lines.get(0);
        List<String> body = null;
        if (lines.size() > 1) {
            body = lines.subList(1, lines.size())
                        .stream()
                        .dropWhile(String::isEmpty)
                        .collect(Collectors.toList());
        } else {
            body = List.of();
        }

        var isDraft = getSwitch("draft", "create", arguments);
        if (upstream.isEmpty() && shouldPublish) {
            GitPublish.main(new String[] { "--quiet", remote });
        }
        var pr = remoteRepo.createPullRequest(parentRepo, targetBranch, currentBranch.name(), title, body, isDraft);
        var assigneesOption = getOption("assignees", "create", arguments);
        if (assigneesOption != null) {
            var usernames = Arrays.asList(assigneesOption.split(","));
            var assignees = usernames.stream()
                                     .map(u -> host.user(u))
                                     .filter(Optional::isPresent)
                                     .map(Optional::get)
                                     .collect(Collectors.toList());
            pr.setAssignees(assignees);
        }
        System.out.println(pr.webUrl().toString());
        Files.deleteIfExists(file);

        repo.config("pr." + currentBranch.name(), "id", pr.id().toString());
    }
}
