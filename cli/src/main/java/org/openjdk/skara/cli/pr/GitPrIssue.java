/*
 * Copyright (c) 2020, Oracle and/or its affiliates. All rights reserved.
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
import org.openjdk.skara.issuetracker.Comment;
import org.openjdk.skara.forge.PullRequest;

import static org.openjdk.skara.cli.pr.Utils.*;

import java.io.IOException;
import java.util.*;

public class GitPrIssue {
    static final List<Flag> flags = List.of(
        Option.shortcut("")
              .fullname("add")
              .describe("ID")
              .helptext("Consider issue solved by this pull request")
              .optional(),
        Option.shortcut("")
              .fullname("remove")
              .describe("ID")
              .helptext("Do not consider issue as solved by this pull request")
              .optional(),
        Option.shortcut("")
              .fullname("priority")
              .describe("1|2|3|4|5")
              .helptext("Priority for issue")
              .optional(),
        Option.shortcut("")
              .fullname("component")
              .describe("NAME")
              .helptext("Component for issue")
              .optional(),
        Switch.shortcut("")
              .fullname("create")
              .helptext("Create an issue for this pull request")
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

        if (arguments.contains("add")) {
            var issueId = arguments.get("add").asString();
            var comment = pr.addComment("/issue add" + " " + issueId);
            showReply(awaitReplyTo(pr, comment));
        } else if (arguments.contains("remove")) {
            var issueId = arguments.get("remove").asString();
            var comment = pr.addComment("/issue remove" + " " + issueId);
            showReply(awaitReplyTo(pr, comment));
        } else if (arguments.contains("create")) {
            if (!arguments.contains("component")) {
                System.err.println("error: no component specified, use --component");
                System.exit(1);
            }
            var component = arguments.get("component").asString();
            var prio = arguments.get("priority").orString("4");
            if (!List.of("1", "2", "3", "4", "5").contains(prio)) {
                System.err.println("error: unsupported priority: " + prio);
                System.err.println("       Supported priorities are: 1,2,3,4,5");
                System.exit(1);
            }
            var comment = pr.addComment("/issue create P" + prio + " " + component);
            showReply(awaitReplyTo(pr, comment));
        } else {
            System.err.println("error: must use either --add, --remove or --create");
            System.exit(1);
        }
    }
}
