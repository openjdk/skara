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
package org.openjdk.skara.bots.notify;

import org.openjdk.skara.forge.HostedRepository;
import org.openjdk.skara.vcs.*;

import java.io.*;
import java.time.format.DateTimeFormatter;

public class CommitFormatters {
    public static String toTextBrief(HostedRepository repository, Commit commit) {
        var writer = new StringWriter();
        var printer = new PrintWriter(writer);

        printer.println("Changeset: " + commit.hash().abbreviate());
        printer.println("Author:    " + commit.author().name() + " <" + commit.author().email() + ">");
        if (!commit.author().equals(commit.committer())) {
            printer.println("Committer: " + commit.committer().name() + " <" + commit.committer().email() + ">");
        }
        printer.println("Date:      " + commit.date().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss +0000")));
        printer.println("URL:       " + repository.webUrl(commit.hash()));

        return writer.toString();
    }

    private static String patchToText(Patch patch) {
        if (patch.status().isAdded()) {
            return "+ " + patch.target().path().orElseThrow();
        } else if (patch.status().isDeleted()) {
            return "- " + patch.source().path().orElseThrow();
        } else if (patch.status().isModified()) {
            return "! " + patch.target().path().orElseThrow();
        } else {
            return "= " + patch.target().path().orElseThrow();
        }
    }

    public static String toText(HostedRepository repository, Commit commit) {
        var writer = new StringWriter();
        var printer = new PrintWriter(writer);

        printer.print(toTextBrief(repository, commit));
        printer.println();
        printer.println(String.join("\n", commit.message()));
        printer.println();

        for (var diff : commit.parentDiffs()) {
            for (var patch : diff.patches()) {
                printer.println(patchToText(patch));
            }
        }

        return writer.toString();
    }
}
