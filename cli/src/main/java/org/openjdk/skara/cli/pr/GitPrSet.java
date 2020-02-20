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
            Option.shortcut("")
                  .fullname("assignees")
                  .describe("LIST")
                  .helptext("Comma separated list of assignees")
                  .optional(),
            Switch.shortcut("")
                  .fullname("no-draft")
                  .helptext("Hide all pull requests in draft state")
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
        var id = pullRequestIdArgument(repo, arguments);
        var pr = getPullRequest(uri, repo, host, id);

        var assigneesOption = getOption("assignees", "set", arguments);
        if (assigneesOption != null) {
            var usernames = Arrays.asList(assigneesOption.split(","));
            var assignees = usernames.stream()
                .map(u -> host.user(u))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toList());
            pr.setAssignees(assignees);
        }

        var setNoDraft = getSwitch("no-draft", "set", arguments);
        if (setNoDraft) {
            pr.makeNotDraft();
        }
    }
}
