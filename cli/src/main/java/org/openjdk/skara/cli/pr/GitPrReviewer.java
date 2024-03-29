/*
 * Copyright (c) 2020, 2023, Oracle and/or its affiliates. All rights reserved.
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
import org.openjdk.skara.cli.ForgeUtils;

import static org.openjdk.skara.cli.pr.Utils.*;

import java.io.IOException;
import java.util.*;

public class GitPrReviewer {
    static final List<Flag> flags = List.of(
        Option.shortcut("")
              .fullname("credit")
              .describe("USERNAME")
              .helptext("Credit a person as a reviewer of this pull request")
              .optional(),
        Option.shortcut("")
              .fullname("remove")
              .describe("USERNAME")
              .helptext("Do not consider pull request reviewed by this user")
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
        var parser = new ArgumentParser("git-pr reviewer", flags, inputs);
        var arguments = parse(parser, args);
        var repo = getRepo();
        var uri = getURI(repo, arguments);
        var host = getForge(uri, repo, arguments);
        var id = pullRequestIdArgument(repo, arguments);
        var pr = getPullRequest(uri, repo, host, id);

        if (arguments.contains("credit")) {
            var username = ForgeUtils.getOption("credit", arguments);
            var comment = pr.addComment("/reviewer credit" + " " + username);
            showReply(awaitReplyTo(pr, comment));
        } else if (arguments.contains("remove")) {
            var username = ForgeUtils.getOption("remove", arguments);
            var comment = pr.addComment("/reviewer remove" + " " + username);
            showReply(awaitReplyTo(pr, comment));
        } else {
            System.err.println("error: must use either --credit or --remove");
            System.exit(1);
        }
    }
}
