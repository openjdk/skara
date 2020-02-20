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

import static org.openjdk.skara.cli.pr.Utils.*;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

public class GitPrStatus {
    public static void main(String[] args) throws IOException {
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
            Switch.shortcut("")
                  .fullname("no-decoration")
                  .helptext("Hide any decorations when listing PRs")
                  .optional(),
            Switch.shortcut("")
                  .fullname("no-token")
                  .helptext("Do not use a personal access token (PAT)")
                  .optional(),
            Switch.shortcut("")
                  .fullname("no-checks")
                  .helptext("Do not show check status as part of the 'git pr status' output")
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

        var noDecoration = getSwitch("no-decoration", "status", arguments);
        var decoration = noDecoration ? "" : "Status: ";
        System.out.println(decoration + statusForPullRequest(pr));

        var noChecks = getSwitch("no-checks", "status", arguments);
        if (!noChecks) {
            var checks = pr.checks(pr.headHash());
            var jcheck = Optional.ofNullable(checks.get("jcheck"));
            var submit = Optional.ofNullable(checks.get("submit"));
            var showChecks = jcheck.isPresent() || submit.isPresent();
            if (showChecks) {
                System.out.println("Checks:");
                if (jcheck.isPresent()) {
                    System.out.println("- jcheck: " + statusForCheck(jcheck.get()));
                }
                if (submit.isPresent()) {
                    System.out.println("- submit: " + statusForCheck(submit.get()));
                }
            }
        }
    }
}
