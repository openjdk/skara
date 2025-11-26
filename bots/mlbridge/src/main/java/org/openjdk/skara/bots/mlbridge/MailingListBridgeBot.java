/*
 * Copyright (c) 2019, 2022, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.bots.mlbridge;

import org.openjdk.skara.bot.*;
import org.openjdk.skara.email.EmailAddress;
import org.openjdk.skara.forge.*;

import java.net.URI;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import org.openjdk.skara.mailinglist.MailingListServer;

public class MailingListBridgeBot implements Bot {
    private final EmailAddress emailAddress;
    private final HostedRepository codeRepo;
    private final HostedRepository archiveRepo;
    private final String archiveRef;
    private final HostedRepository censusRepo;
    private final String censusRef;
    private final List<MailingListConfiguration> lists;
    private final Set<String> ignoredUsers;
    private final Set<Pattern> ignoredComments;
    private final WebrevStorage webrevStorage;
    private final Set<String> readyLabels;
    private final Map<String, Pattern> readyComments;
    private final Map<String, String> headers;
    private final URI issueTracker;
    private final Duration cooldown;
    private final boolean repoInSubject;
    private final Pattern branchInSubject;
    private final Path seedStorage;
    private final PullRequestPoller poller;
    private final MailingListServer mailingListServer;

    private final Logger log = Logger.getLogger("org.openjdk.skara.bots.mlbridge");

    private volatile boolean labelsUpdated = false;

    MailingListBridgeBot(EmailAddress from, HostedRepository repo, HostedRepository archive, String archiveRef,
                         HostedRepository censusRepo, String censusRef, List<MailingListConfiguration> lists,
                         Set<String> ignoredUsers, Set<Pattern> ignoredComments,
                         HostedRepository webrevStorageHTMLRepository, HostedRepository webrevStorageJSONRepository,
                         String webrevStorageRef, Path webrevStorageBase, URI webrevStorageBaseUri,
                         boolean webrevGenerateHTML, boolean webrevGenerateJSON, Set<String> readyLabels,
                         Map<String, Pattern> readyComments, URI issueTracker, Map<String, String> headers,
                         Duration cooldown, boolean repoInSubject, Pattern branchInSubject,
                         Path seedStorage, MailingListServer mailingListServer) {
        emailAddress = from;
        codeRepo = repo;
        archiveRepo = archive;
        this.archiveRef = archiveRef;
        this.censusRepo = censusRepo;
        this.censusRef = censusRef;
        this.lists = lists;
        this.ignoredUsers = ignoredUsers;
        this.ignoredComments = ignoredComments;
        this.readyLabels = readyLabels;
        this.readyComments = readyComments;
        this.headers = headers;
        this.issueTracker = issueTracker;
        this.cooldown = cooldown;
        this.repoInSubject = repoInSubject;
        this.branchInSubject = branchInSubject;
        this.seedStorage = seedStorage;
        this.mailingListServer = mailingListServer;

        webrevStorage = new WebrevStorage(webrevStorageHTMLRepository, webrevStorageJSONRepository, webrevStorageRef,
                                          webrevStorageBase, webrevStorageBaseUri, from,
                                          webrevGenerateHTML, webrevGenerateJSON);
        poller = new PullRequestPoller(codeRepo, true);
    }

    static MailingListBridgeBotBuilder newBuilder() {
        return new MailingListBridgeBotBuilder();
    }

    HostedRepository codeRepo() {
        return codeRepo;
    }

    HostedRepository archiveRepo() {
        return archiveRepo;
    }

    String archiveRef() {
        return archiveRef;
    }

    HostedRepository censusRepo() {
        return censusRepo;
    }

    String censusRef() {
        return censusRef;
    }

    EmailAddress emailAddress() {
        return emailAddress;
    }

    List<MailingListConfiguration> lists() {
        return lists;
    }

    Duration cooldown() {
        return cooldown;
    }

    Set<String> ignoredUsers() {
        return ignoredUsers;
    }

    Set<Pattern> ignoredComments() {
        return ignoredComments;
    }

    WebrevStorage webrevStorage() {
        return webrevStorage;
    }

    Set<String> readyLabels() {
        return readyLabels;
    }

    Map<String, Pattern> readyComments() {
        return readyComments;
    }

    Map<String, String> headers() {
        return headers;
    }

    URI issueTracker() {
        return issueTracker;
    }

    boolean repoInSubject() {
        return repoInSubject;
    }

    Pattern branchInSubject() {
        return branchInSubject;
    }

    Optional<Path> seedStorage() {
        return Optional.ofNullable(seedStorage);
    }

    public boolean labelsUpdated() {
        return labelsUpdated;
    }

    public void setLabelsUpdated(boolean labelsUpdated) {
        this.labelsUpdated = labelsUpdated;
    }

    public MailingListServer mailingListServer() {
        return mailingListServer;
    }

    @Override
    public List<WorkItem> getPeriodicItems() {
        List<WorkItem> ret = new LinkedList<>();

        if (!labelsUpdated) {
            ret.add(new LabelsUpdaterWorkItem(this));
        }

        List<PullRequest> prs = poller.updatedPullRequests();
        prs.stream()
                .map(pr -> new ArchiveWorkItem(pr, this,
                        e -> poller.retryPullRequest(pr),
                        r -> poller.quarantinePullRequest(pr, r)))
                .forEach(ret::add);
        poller.lastBatchHandled();

        return ret;
    }

    @Override
    public String name() {
        return MailingListBridgeBotFactory.NAME;
    }

    @Override
    public String toString() {
        return "MailingListBridgeBot@" + codeRepo.name();
    }
}
