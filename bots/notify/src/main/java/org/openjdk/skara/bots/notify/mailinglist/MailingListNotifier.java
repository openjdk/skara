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
package org.openjdk.skara.bots.notify.mailinglist;

import org.openjdk.skara.bots.notify.*;
import org.openjdk.skara.email.*;
import org.openjdk.skara.forge.*;
import org.openjdk.skara.mailinglist.MailingList;
import org.openjdk.skara.vcs.*;
import org.openjdk.skara.vcs.openjdk.OpenJDKTag;

import java.io.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

class MailingListNotifier implements Notifier, RepositoryListener {
    private final MailingList list;
    private final EmailAddress recipient;
    private final EmailAddress sender;
    private final EmailAddress author;
    private final boolean includeBranch;
    private final boolean reportNewTags;
    private final boolean reportNewBranches;
    private final boolean reportNewBuilds;
    private final Mode mode;
    private final Map<String, String> headers;
    private final Pattern allowedAuthorDomains;
    private final Logger log = Logger.getLogger("org.openjdk.skara.bots.notify");

    public enum Mode {
        ALL,
        PR
    }

    MailingListNotifier(MailingList list, EmailAddress recipient, EmailAddress sender, EmailAddress author,
                       boolean includeBranch, boolean reportNewTags, boolean reportNewBranches, boolean reportNewBuilds,
                       Mode mode, Map<String, String> headers, Pattern allowedAuthorDomains) {
        this.list = list;
        this.recipient = recipient;
        this.sender = sender;
        this.author = author;
        this.includeBranch = includeBranch;
        this.reportNewTags = reportNewTags;
        this.reportNewBranches = reportNewBranches;
        this.reportNewBuilds = reportNewBuilds;
        this.mode = mode;
        this.headers = headers;
        this.allowedAuthorDomains = allowedAuthorDomains;
    }

    public static MailingListNotifierBuilder newBuilder() {
        return new MailingListNotifierBuilder();
    }

    private String tagAnnotationToText(HostedRepository repository, Tag.Annotated annotation) {
        var writer = new StringWriter();
        var printer = new PrintWriter(writer);

        printer.println("Tagged by: " + annotation.author().name() + " <" + annotation.author().email() + ">");
        printer.println("Date:      " + annotation.date().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss +0000")));
        printer.println();
        printer.print(String.join("\n", annotation.message()));

        return writer.toString();
    }

    private EmailAddress filteredAuthor(EmailAddress commitAddress) {
        if (author != null) {
            return author;
        }
        var allowedAuthorMatcher = allowedAuthorDomains.matcher(commitAddress.domain());
        if (!allowedAuthorMatcher.matches()) {
            return sender;
        } else {
            return commitAddress;
        }
    }

    private EmailAddress commitToAuthor(Commit commit) {
        return filteredAuthor(EmailAddress.from(commit.committer().name(), commit.committer().email()));
    }

    private EmailAddress annotationToAuthor(Tag.Annotated annotation) {
        return filteredAuthor(EmailAddress.from(annotation.author().name(), annotation.author().email()));
    }

    private String commitsToSubject(HostedRepository repository, List<Commit> commits, Branch branch) {
        var subject = new StringBuilder();
        subject.append(repository.repositoryType().shortName());
        subject.append(": ");
        subject.append(repository.name());
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

    private String tagToSubject(HostedRepository repository, Hash hash, Tag tag) {
        return repository.repositoryType().shortName() +
                ": " +
                repository.name() +
                ": Added tag " +
                tag +
                " for changeset " +
                hash.abbreviate();
    }

    private List<Commit> filterPrCommits(HostedRepository repository, Repository localRepository, List<Commit> commits, Branch branch) throws NonRetriableException {
        var ret = new ArrayList<Commit>();
        var mergedCommits = new HashSet<Hash>();

        for (var commit : commits) {
            var candidates = repository.findPullRequestsWithComment(null, "Pushed as commit " + commit.hash() + ".");
            if (candidates.size() != 1) {
                if (candidates.size() > 1) {
                    log.warning("Commit " + commit.hash() + " matches " + candidates.size() + " pull requests - expected 1");
                }
                ret.add(commit);
                continue;
            }

            var candidate = candidates.get(0);
            var prLink = candidate.webUrl();
            if (!candidate.targetRef().equals(branch.name())) {
                log.info("Pull request " + prLink + " targets " + candidate.targetRef() + " - commit is on " + branch.toString() + " - skipping");
                ret.add(commit);
                continue;
            }

            // For a merge PR, many other of these commits could belong here as well
            if (commit.parents().size() > 1) {
                if (!PullRequestUtils.isMerge(candidate)) {
                    log.warning("Merge commit from non-merge PR?");
                    ret.add(commit);
                    continue;
                }

                // For a merge PR, the first parent is always the target branch, so skip that one
                for (int i = 1; i < commit.parents().size(); ++i) {
                    try {
                        localRepository.commitMetadata(commit.parents().get(0), commit.parents().get(i))
                                       .forEach(c -> mergedCommits.add(c.hash()));
                    } catch (IOException e) {
                        log.warning("Unable to check if commits between " + commit.parents().get(0) + " and "
                                            + commit.parents().get(i) + " were brought in through merging in " + prLink);
                    }
                }
            }
        }

        return ret.stream()
                  .filter(c -> !mergedCommits.contains(c.hash()))
                  .collect(Collectors.toList());
    }

    private void sendCombinedCommits(HostedRepository repository, List<Commit> commits, Branch branch) throws NonRetriableException {
        if (commits.size() == 0) {
            return;
        }

        var writer = new StringWriter();
        var printer = new PrintWriter(writer);

        for (var commit : commits) {
            printer.println(CommitFormatters.toText(repository, commit));
        }

        var subject = commitsToSubject(repository, commits, branch);
        var lastCommit = commits.get(commits.size() - 1);
        var commitAddress = filteredAuthor(EmailAddress.from(lastCommit.committer().name(), lastCommit.committer().email()));
        var email = Email.create(subject, writer.toString())
                         .sender(sender)
                         .author(commitAddress)
                         .recipient(recipient)
                         .headers(headers)
                         .headers(commitHeaders(repository, commits))
                         .build();

        try {
            list.post(email);
        } catch (RuntimeException e) {
            throw new NonRetriableException(e);
        }
    }

    private Map<String, String> commitHeaders(HostedRepository repository, List<Commit> commits) {
        var ret = new HashMap<String, String>();
        ret.put("X-Git-URL", repository.webUrl().toString());
        if (!commits.isEmpty()) {
            ret.put("X-Git-Changeset", commits.get(0).hash().hex());
        }
        return ret;
    }

    @Override
    public void attachTo(Emitter e) {
        e.registerRepositoryListener(this);
    }

    @Override
    public void onNewCommits(HostedRepository repository, Repository localRepository, List<Commit> commits, Branch branch) throws NonRetriableException {
        if (mode == Mode.PR) {
            commits = filterPrCommits(repository, localRepository, commits, branch);
        }
        sendCombinedCommits(repository, commits, branch);
    }

    @Override
    public void onNewOpenJDKTagCommits(HostedRepository repository, Repository localRepository, List<Commit> commits, OpenJDKTag tag, Tag.Annotated annotation) throws NonRetriableException {
        if (!reportNewTags) {
            return;
        }
        if (!reportNewBuilds) {
            onNewTagCommit(repository, localRepository, commits.get(commits.size() - 1), tag.tag(), annotation);
            return;
        }
        var writer = new StringWriter();
        var printer = new PrintWriter(writer);

        var taggedCommit = commits.get(commits.size() - 1);
        if (annotation != null) {
            printer.println(tagAnnotationToText(repository, annotation));
        }
        printer.println(CommitFormatters.toTextBrief(repository, taggedCommit));

        printer.println("The following commits are included in " + tag.tag());
        printer.println("========================================================");
        for (var commit : commits) {
            printer.print(commit.hash().abbreviate());
            if (commit.message().size() > 0) {
                printer.print(": " + commit.message().get(0));
            }
            printer.println();
        }

        var subject = tagToSubject(repository, taggedCommit.hash(), tag.tag());
        var email = Email.create(subject, writer.toString())
                         .sender(sender)
                         .recipient(recipient)
                         .headers(headers)
                         .headers(commitHeaders(repository, commits));

        if (annotation != null) {
            email.author(annotationToAuthor(annotation));
        } else {
            email.author(commitToAuthor(taggedCommit));
        }

        try {
            list.post(email.build());
        } catch (RuntimeException e) {
            throw new NonRetriableException(e);
        }
    }

    @Override
    public void onNewTagCommit(HostedRepository repository, Repository localRepository, Commit commit, Tag tag, Tag.Annotated annotation) throws NonRetriableException {
        if (!reportNewTags) {
            return;
        }
        var writer = new StringWriter();
        var printer = new PrintWriter(writer);

        if (annotation != null) {
            printer.println(tagAnnotationToText(repository, annotation));
        }
        printer.println(CommitFormatters.toTextBrief(repository, commit));

        var subject = tagToSubject(repository, commit.hash(), tag);
        var email = Email.create(subject, writer.toString())
                         .sender(sender)
                         .recipient(recipient)
                         .headers(headers)
                         .headers(commitHeaders(repository, List.of(commit)));

        if (annotation != null) {
            email.author(annotationToAuthor(annotation));
        } else {
            email.author(commitToAuthor(commit));
        }

        try {
            list.post(email.build());
        } catch (RuntimeException e) {
            throw new NonRetriableException(e);
        }
    }

    private String newBranchSubject(HostedRepository repository, Repository localRepository, List<Commit> commits, Branch parent, Branch branch) {
        var subject = new StringBuilder();
        subject.append(repository.repositoryType().shortName());
        subject.append(": ");
        subject.append(repository.name());
        subject.append(": created branch ");
        subject.append(branch);
        subject.append(" based on the branch ");
        subject.append(parent);
        subject.append(" containing ");
        subject.append(commits.size());
        subject.append(" unique commit");
        if (commits.size() != 1) {
            subject.append("s");
        }

        return subject.toString();
    }

    @Override
    public void onNewBranch(HostedRepository repository, Repository localRepository, List<Commit> commits, Branch parent, Branch branch) throws NonRetriableException {
        if (!reportNewBranches) {
            return;
        }
        var writer = new StringWriter();
        var printer = new PrintWriter(writer);

        if (commits.size() > 0) {
            printer.println("The following commits are unique to the " + branch.name() + " branch:");
            printer.println("========================================================");
            for (var commit : commits) {
                printer.print(commit.hash().abbreviate());
                if (commit.message().size() > 0) {
                    printer.print(": " + commit.message().get(0));
                }
                printer.println();
            }
        } else {
            printer.println("The new branch " + branch.name() + " is currently identical to the " + parent.name() + " branch.");
        }

        var subject = newBranchSubject(repository, localRepository, commits, parent, branch);
        var finalAuthor = commits.size() > 0 ? commitToAuthor(commits.get(commits.size() - 1)) : sender;

        var email = Email.create(subject, writer.toString())
                         .sender(sender)
                         .author(finalAuthor)
                         .recipient(recipient)
                         .headers(headers)
                         .headers(commitHeaders(repository, commits))
                         .build();
        try {
            list.post(email);
        } catch (RuntimeException e) {
            throw new NonRetriableException(e);
        }
    }

    @Override
    public String name() {
        return "ml";
    }
}
