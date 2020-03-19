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
import org.openjdk.skara.forge.*;
import org.openjdk.skara.host.*;
import org.openjdk.skara.issuetracker.IssueTracker;
import org.openjdk.skara.issuetracker.Issue;
import org.openjdk.skara.jcheck.JCheckConfiguration;
import org.openjdk.skara.proxy.HttpProxy;
import org.openjdk.skara.vcs.*;
import org.openjdk.skara.vcs.openjdk.CommitMessageParsers;
import org.openjdk.skara.version.Version;

import static org.openjdk.skara.cli.pr.Utils.*;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.stream.Collectors;

public class GitPrSet {
    static final List<Flag> flags = List.of(
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
        Option.shortcut("")
              .fullname("assignees")
              .describe("LIST")
              .helptext("Comma separated list of assignees")
              .optional(),
        Option.shortcut("")
              .fullname("title")
              .describe("MESSAGE")
              .helptext("The title of the pull request")
              .optional(),
        Switch.shortcut("")
              .fullname("open")
              .helptext("Set the pull request's state to open")
              .optional(),
        Switch.shortcut("")
              .fullname("closed")
              .helptext("Set the pull request's state to closed")
              .optional(),
        Switch.shortcut("")
              .fullname("body")
              .helptext("Set the body of the pull request")
              .optional(),
        Switch.shortcut("")
              .fullname("no-draft")
              .helptext("Mark the pull request as not draft")
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

    static final List<Input> inputs = List.of(
        Input.position(0)
             .describe("ID")
             .singular()
             .optional()
    );

    public static void main(String[] args) throws IOException, InterruptedException {
        var parser = new ArgumentParser("git-pr", flags, inputs);
        var arguments = parse(parser, args);
        var repo = getRepo();
        var uri = getURI(repo, arguments);
        var host = getForge(uri, repo, arguments);
        var id = pullRequestIdArgument(repo, arguments);
        var pr = getPullRequest(uri, repo, host, id);

        var assigneesOption = getOption("assignees", "set", arguments);
        if (assigneesOption == null) {
            pr.setAssignees(List.of());
        } else {
            var usernames = Arrays.asList(assigneesOption.split(","));
            var assignees = usernames.stream()
                .map(u -> host.user(u))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toList());
            pr.setAssignees(assignees);
        }

        var title = getOption("title", "set", arguments);
        if (title != null) {
            pr.setTitle(title);
        }

        var setOpen = getSwitch("open", "set", arguments);
        if (setOpen) {
            pr.setState(PullRequest.State.OPEN);
        }

        var setClosed = getSwitch("closed", "set", arguments);
        if (setClosed) {
            pr.setState(PullRequest.State.CLOSED);
        }

        var setBody = getSwitch("body", "set", arguments);
        if (setBody) {
            var file = Files.createTempFile("PULL_REQUEST_", ".md");
            Files.writeString(file, pr.body());
            var success = spawnEditor(repo, file);
            if (!success) {
                System.err.println("error: editor exited with non-zero status code, aborting");
                System.exit(1);
            }
            var content = Files.readString(file);
            if (content.isEmpty()) {
                System.err.println("error: no message present, aborting");
                System.exit(1);
            }
            pr.setBody(content);
        }

        var setNoDraft = getSwitch("no-draft", "set", arguments);
        if (setNoDraft) {
            pr.makeNotDraft();
        }
    }
}
