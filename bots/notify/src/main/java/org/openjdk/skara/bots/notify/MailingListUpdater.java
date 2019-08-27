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

import org.openjdk.skara.email.*;
import org.openjdk.skara.host.HostedRepository;
import org.openjdk.skara.vcs.*;
import org.openjdk.skara.vcs.openjdk.*;

import java.io.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class MailingListUpdater implements UpdateConsumer {
    private final String host;
    private final EmailAddress recipient;
    private final EmailAddress sender;
    private final boolean includeBranch;

    MailingListUpdater(String host, EmailAddress recipient, EmailAddress sender, boolean includeBranch) {
        this.host = host;
        this.recipient = recipient;
        this.sender = sender;
        this.includeBranch = includeBranch;
    }

    private String patchToText(Patch patch) {
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

    private String commitToText(HostedRepository repository, Commit commit) {
        var writer = new StringWriter();
        var printer = new PrintWriter(writer);

        printer.println("Changeset: " + commit.hash().abbreviate());
        printer.println("Author:    " + commit.author().name() + " <" + commit.author().email() + ">");
        if (!commit.author().equals(commit.committer())) {
            printer.println("Committer: " + commit.committer().name() + " <" + commit.committer().email() + ">");
        }
        printer.println("Date:      " + commit.date().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss +0000")));
        printer.println("URL:       " + repository.getWebUrl(commit.hash()));
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

    private String commitsToSubject(HostedRepository repository, List<Commit> commits, Branch branch) {
        var subject = new StringBuilder();
        subject.append(repository.getRepositoryType().shortName());
        subject.append(": ");
        subject.append(repository.getName());
        subject.append(": ");
        if (includeBranch) {
            subject.append(branch.name());
            subject.append(": ");
        }
        if (commits.size() > 1) {
            subject.append(commits.size());
            subject.append(" new changesets");
        } else {
            subject.append(commits.get(0).message().get(0));
        }
        return subject.toString();
    }

    @Override
    public void handleCommits(HostedRepository repository, List<Commit> commits, Branch branch) {
        var writer = new StringWriter();
        var printer = new PrintWriter(writer);

        var subject = commitsToSubject(repository, commits, branch);

        for (var commit : commits) {
            printer.println(commitToText(repository, commit));
        }

        var email = Email.create(sender, subject, writer.toString())
                         .recipient(recipient)
                         .build();

        try {
            SMTP.send(host, recipient, email);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        System.out.print(writer.toString());
    }

    @Override
    public void handleTagCommits(HostedRepository repository, List<Commit> commits, OpenJDKTag tag) {

    }
}
