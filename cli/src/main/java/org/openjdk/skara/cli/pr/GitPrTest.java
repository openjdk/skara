/*
 * Copyright (c) 2019, 2023, Oracle and/or its affiliates. All rights reserved.
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
import java.util.List;

public class GitPrTest {
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
        Option.shortcut("j")
              .fullname("job")
              .describe("NAME")
              .helptext("Name of a job")
              .optional(),
        Switch.shortcut("")
              .fullname("approve")
              .helptext("Approve a test request")
              .optional(),
        Switch.shortcut("")
              .fullname("cancel")
              .helptext("Cancel a test request")
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
        var parser = new ArgumentParser("git-pr test", flags, inputs);
        var arguments = parse(parser, args);
        var repo = getRepo();
        var uri = getURI(repo, arguments);
        var host = getForge(uri, repo, arguments);
        var id = pullRequestIdArgument(repo, arguments);
        var pr = getPullRequest(uri, repo, host, id);
        var head = pr.headHash();

        var command = "/test";
        if (arguments.contains("approve")) {
            command += " approve";
        } else if (arguments.contains("cancel")) {
            command += " cancel";
        } else if (arguments.contains("job")) {
            command += ForgeUtils.getOption("job", arguments);
        }
        var testComment = pr.addComment(command);

        var seenTestComment = false;
        for (var i = 0; i < 90; i++) {
            var comments = pr.comments();
            for (var comment : comments) {
                if (!seenTestComment) {
                    if (comment.id().equals(testComment.id())) {
                        seenTestComment = true;
                    }
                    continue;
                }
                var lines = comment.body().split("\n");
                var n = lines.length;
                if (n > 0) {
                    if (n == 4 &&
                        lines[0].equals("<!-- TEST STARTED -->") &&
                        lines[1].startsWith("<!-- github.com-") &&
                        lines[2].equals("<!-- " + head.hex() + " -->")) {
                        var output = removeTrailing(lines[3], ".");
                        System.out.println(output);
                        System.exit(0);
                    } else if (n == 2 &&
                               lines[0].equals("<!-- TEST ERROR -->")) {
                        var output = removeTrailing(lines[1], ".");
                        System.out.println(output);
                        System.exit(1);
                    } else if (n == 4 &&
                               lines[0].equals("<!-- TEST PENDING -->") &&
                               lines[1].equals("<!--- " + head.hex() + " -->")) {
                        var output = removeTrailing(lines[3], ".");
                        System.out.println(output);
                        System.exit(0);
                    }
                }
            }

            Thread.sleep(2000);
        }
    }
}
