/*
 * Copyright (c) 2019, 2021, Oracle and/or its affiliates. All rights reserved.
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
import org.openjdk.skara.forge.Review;

import static org.openjdk.skara.cli.pr.Utils.*;

import java.io.IOException;
import java.util.List;

public class GitPrReview {
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
        Option.shortcut("m")
              .fullname("message")
              .describe("TEXT")
              .helptext("Message to author as part of review (e.g. \"Looks good!\")")
              .optional(),
        Option.shortcut("t")
              .fullname("type")
              .describe("TEXT")
              .helptext("Select the review type: 'approve' or 'request-changes' or 'comment'")
              .required(),
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

    public static void main(String[] args) throws IOException {
        var parser = new ArgumentParser("git-pr review", flags, inputs);
        var arguments = parse(parser, args);
        var repo = getRepo();
        var uri = getURI(repo, arguments);
        var host = getForge(uri, repo, arguments);
        var id = pullRequestIdArgument(repo, arguments);
        var pr = getPullRequest(uri, repo, host, id);

        var message = arguments.contains("message") ?
            arguments.get("message").asString() :
            null;
        var type = arguments.get("type").asString();
        checkType(type, parser);
        if ("approve".equals(type)) {
            pr.addReview(Review.Verdict.APPROVED, message);
        } else if ("request-changes".equals(type)) {
            checkMessage(message, type, parser);
            pr.addReview(Review.Verdict.DISAPPROVED, message);
        } else if ("comment".equals(type)) {
            checkMessage(message, type, parser);
            pr.addReview(Review.Verdict.NONE, message);
        }
    }

    /**
     * The message can't be null if the type is `request-change` or `comment`.
     */
    public static void checkMessage(String message, String type, ArgumentParser parser) {
        if (message == null) {
            System.err.println("error: the option 'message' missed. Need to provide the 'message' if the 'type' is '" + type + "'.");
            parser.showUsage();
            System.exit(1);
        }
    }

    /**
     * The type need to be `approve` or `request-change` or `comment`.
     */
    public static void checkType(String type, ArgumentParser parser) {
        if ("approve".equals(type) || "request-changes".equals(type) || "comment".equals(type)) {
            return;
        }
        System.err.println("error: incorrect review 'type': '" + type
                + "'. Supported review types:  \"approve\", \"request-changes\" or \"comment\".");
        parser.showUsage();
        System.exit(1);
    }
}
