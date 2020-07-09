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
import java.util.stream.Collectors;

public class GitPrCC {
    static final List<Flag> flags = List.of(
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
             .describe("ARG")
             .trailing()
             .required()
    );

    public static void main(String[] args) throws IOException, InterruptedException {
        var parser = new ArgumentParser("git-pr cc", flags, inputs);
        var arguments = parse(parser, args);
        var repo = getRepo();
        var uri = getURI(repo, arguments);
        var host = getForge(uri, repo, arguments);
        var prId = arguments.at(0).asString().matches("[0-9]+") ?
            arguments.at(0).asString() : null;
        var pr = getPullRequest(uri, repo, host, prId);

        var labelsStartIndex = prId == null ? 0 : 1;
        var labels = arguments.inputs()
                              .stream()
                              .skip(labelsStartIndex)
                              .map(Argument::asString)
                              .collect(Collectors.joining(" "));
        var comment = pr.addComment("/cc " + labels);
        showReply(awaitReplyTo(pr, comment));
    }
}
