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
import org.openjdk.skara.mailinglist.*;
import org.openjdk.skara.vcs.*;
import org.openjdk.skara.vcs.openjdk.OpenJDKTag;

import java.io.*;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.logging.Logger;

public class MailingListUpdater implements UpdateConsumer {
    private final MailingList list;
    private final EmailAddress recipient;
    private final EmailAddress sender;
    private final boolean includeBranch;
    private final Mode mode;
    private final Map<String, String> headers;
    private final Logger log = Logger.getLogger("org.openjdk.skara.bots.notify");

    enum Mode {
        ALL,
        PR,
        PR_ONLY
    }

    MailingListUpdater(MailingList list, EmailAddress recipient, EmailAddress sender, boolean includeBranch, Mode mode,
                       Map<String, String> headers) {
        this.list = list;
        this.recipient = recipient;
        this.sender = sender;
        this.includeBranch = includeBranch;
        this.mode = mode;
        this.headers = headers;
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

    private List<Commit> filterAndSendPrCommits(HostedRepository repository, List<Commit> commits) {
        var ret = new ArrayList<Commit>();

        var rfrs = list.conversations(Duration.ofDays(365)).stream()
                       .map(Conversation::first)
                       .filter(email -> email.subject().startsWith("RFR: "))
                       .collect(Collectors.toList());

        for (var commit : commits) {
            var candidates = repository.findPullRequestsWithComment(null, "Pushed as commit " + commit.hash() + ".");
            if (candidates.size() != 1) {
                log.warning("Commit " + commit.hash() + " matches " + candidates.size() + " pull requests - expected 1");
                ret.add(commit);
                continue;
            }

            var candidate = candidates.get(0);
            var prLink = candidate.getWebUrl();
            var prLinkPattern = Pattern.compile("^(?:PR: )?" + Pattern.quote(prLink.toString()), Pattern.MULTILINE);

            var rfrCandidates = rfrs.stream()
                                    .filter(email -> prLinkPattern.matcher(email.body()).find())
                                    .collect(Collectors.toList());
            if (rfrCandidates.size() != 1) {
                log.warning("Pull request " + prLink + " found in " + rfrCandidates.size() + " RFR threads - expected 1");
                ret.add(commit);
                continue;
            }
            var rfr = rfrCandidates.get(0);
            var body = commitToText(repository, commit);
            var email = Email.reply(rfr, "Re: [Integrated] " + rfr.subject(), body)
                             .author(sender)
                             .recipient(recipient)
                             .headers(headers)
                             .build();
            list.post(email);
        }

        return ret;
    }

    private void sendCombinedCommits(HostedRepository repository, List<Commit> commits, Branch branch) {
        if (commits.size() == 0) {
            return;
        }

        var writer = new StringWriter();
        var printer = new PrintWriter(writer);

        for (var commit : commits) {
            printer.println(commitToText(repository, commit));
        }

        var subject = commitsToSubject(repository, commits, branch);
        var email = Email.create(sender, subject, writer.toString())
                         .recipient(recipient)
                         .headers(headers)
                         .build();

        list.post(email);
    }

    @Override
    public void handleCommits(HostedRepository repository, List<Commit> commits, Branch branch) {
        switch (mode) {
            case PR_ONLY:
                filterAndSendPrCommits(repository, commits);
                break;
            case PR:
                commits = filterAndSendPrCommits(repository, commits);
                // fall-through
            case ALL:
                sendCombinedCommits(repository, commits, branch);
                break;
        }
    }

    @Override
    public void handleTagCommits(HostedRepository repository, List<Commit> commits, OpenJDKTag tag) {

    }
}
