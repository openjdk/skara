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
package org.openjdk.skara.cli;

import org.openjdk.skara.args.*;
import org.openjdk.skara.vcs.*;
import org.openjdk.skara.issuetracker.IssueTracker;
import org.openjdk.skara.jcheck.*;
import org.openjdk.skara.vcs.openjdk.*;
import org.openjdk.skara.proxy.HttpProxy;
import org.openjdk.skara.version.Version;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class GitInfo {
    private static final URI JBS = URI.create("https://bugs.openjdk.java.net");

    private static void exit(String fmt, Object...args) {
        System.err.println(String.format(fmt, args));
        System.exit(1);
    }

    private static Supplier<IOException> die(String fmt, Object... args) {
        return () -> {
            exit(fmt, args);
            return new IOException();
        };
    }

    private static boolean getSwitch(String name, Arguments arguments, ReadOnlyRepository repo) throws IOException {
        if (arguments.contains(name)) {
            return true;
        }

        var lines = repo.config("info." + name);
        return lines.size() == 1 && lines.get(0).toLowerCase().equals("true");
    }

    private static String jbsProject(ReadOnlyRepository repo, Hash hash) throws IOException {
        var conf = JCheckConfiguration.from(repo, hash).orElseThrow();
        return conf.general().jbs().toUpperCase();
    }

    public static void main(String[] args) throws IOException {
        var flags = List.of(
            Switch.shortcut("m")
                  .fullname("mercurial")
                  .helptext("Deprecated: force use of mercurial")
                  .optional(),
            Switch.shortcut("")
                  .fullname("no-decoration")
                  .helptext("Do not prefix lines with any decoration")
                  .optional(),
            Switch.shortcut("")
                  .fullname("issues")
                  .helptext("Show link(s) to issue(s)")
                  .optional(),
            Switch.shortcut("")
                  .fullname("reviewers")
                  .helptext("Show reviewers")
                  .optional(),
            Switch.shortcut("")
                  .fullname("review")
                  .helptext("Show link to review")
                  .optional(),
            Switch.shortcut("")
                  .fullname("summary")
                  .helptext("Show summary (if present)")
                  .optional(),
            Switch.shortcut("")
                  .fullname("sponsor")
                  .helptext("Show sponsor (if present)")
                  .optional(),
            Switch.shortcut("")
                  .fullname("author")
                  .helptext("Show author")
                  .optional(),
            Switch.shortcut("")
                  .fullname("contributors")
                  .helptext("Show contributors")
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
                  .optional());

        var inputs = List.of(
            Input.position(0)
                 .describe("REV")
                 .singular()
                 .required()
        );

        var parser = new ArgumentParser("git-info", flags, inputs);
        var arguments = parser.parse(args);

        if (arguments.contains("version")) {
            System.out.println("git-info version: " + Version.fromManifest().orElse("unknown"));
            System.exit(0);
        }

        if (arguments.contains("verbose") || arguments.contains("debug")) {
            var level = arguments.contains("debug") ? Level.FINER : Level.FINE;
            Logging.setup(level);
        }

        HttpProxy.setup();
        var isMercurial = arguments.contains("mercurial");
        var ref = arguments.at(0).orString(isMercurial ? "tip" : "HEAD");
        var cwd = Path.of("").toAbsolutePath();
        var repo = ReadOnlyRepository.get(cwd).orElseThrow(die("error: no repository found at " + cwd.toString()));
        var hash = repo.resolve(ref).orElseThrow(die("error: " + ref + " is not a commit"));
        var commits = repo.commits(hash.hex(), 1).asList();
        if (commits.isEmpty()) {
            throw new IOException("internal error: could not list commit for " + hash.hex());
        }
        var commit = commits.get(0);

        var useDecoration = !getSwitch("no-decoration", arguments, repo);
        var useMercurial = getSwitch("mercurial", arguments, repo);

        var showSponsor = getSwitch("sponsor", arguments, repo);
        var showAuthors = getSwitch("authors", arguments, repo);
        var showReviewers = getSwitch("reviewers", arguments, repo);
        var showReview = getSwitch("review", arguments, repo);
        var showSummary = getSwitch("summary", arguments, repo);
        var showIssues = getSwitch("issues", arguments, repo);
        var showTitle = getSwitch("title", arguments, repo);

        if (!showSponsor && !showAuthors && !showReviewers &&
            !showReview && !showSummary && !showIssues && !showTitle) {
            // no switches or configuration provided, show everything by default
            showSponsor = true;
            showAuthors = true;
            showReviewers = true;
            showReview = true;
            showSummary = true;
            showIssues = true;
            showTitle = true;
        }

        var message = useMercurial ?
            CommitMessageParsers.v0.parse(commit) :
            CommitMessageParsers.v1.parse(commit);

        if (showTitle) {
            var decoration = useDecoration ? "Title: " : "";
            System.out.println(decoration + message.title());
        }

        if (showSummary) {
            if (useDecoration && !message.summaries().isEmpty()) {
                System.out.println("Summary:");
            }
            var decoration = useDecoration ? "> " : "";
            for (var line : message.summaries()) {
                System.out.println(decoration + line);
            }
        }

        if (showAuthors) {
            var decoration = "";
            if (useDecoration) {
                decoration = message.contributors().isEmpty() ?
                    "Author: " : "Authors: ";
            }
            var authors = commit.author().toString();
            if (!message.contributors().isEmpty()) {
                var contributorNames = message.contributors()
                                              .stream()
                                              .map(Author::toString)
                                              .collect(Collectors.toList());
                authors += ", " + String.join(", ", contributorNames);
            }
            System.out.println(decoration + authors);
        }

        if (showSponsor) {
            if (!commit.author().equals(commit.committer())) {
                var decoration = useDecoration ? "Sponsor: " : "";
                System.out.println(decoration + commit.committer().toString());
            }
        }

        if (showReviewers) {
            var decoration = "";
            if (useDecoration) {
                decoration = message.reviewers().size() > 1 ?
                    "Reviewers: " : "Reviewer: ";
            }
            System.out.println(decoration + String.join(", ", message.reviewers()));
        }


        if (showReview) {
            var decoration = useDecoration? "Review: " : "";
            var project = jbsProject(repo, hash);
            if (message.issues().size() == 1) {
                var issueId = message.issues().get(0).id();
                var issueTracker = IssueTracker.from("jira", JBS);
                var issue = issueTracker.project(project).issue(issueId);
                if (issue.isPresent()) {
                    for (var link : issue.get().links()) {
                        if (link.title().isPresent() && link.uri().isPresent()) {
                            if (link.title().get().equals("Review")) {
                                System.out.println(decoration + link.uri().get());
                            }
                        }
                    }
                }
            }
        }
        if (showIssues) {
            var project = jbsProject(repo, hash);
            var uri = JBS + "/browse/" + project + "-";
            var issues = message.issues();
            if (issues.size() > 1) {
                if (useDecoration) {
                    System.out.println("Issues:");
                }
                var decoration = useDecoration ? "- " : "";
                for (var issue : issues) {
                    System.out.println(decoration + uri + issue.id());
                }
            } else if (issues.size() == 1) {
                var decoration = useDecoration ? "Issue: " : "";
                System.out.println(decoration + uri + issues.get(0).id());
            }
        }
    }
}
