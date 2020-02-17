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

public class MailingListBridgeBot implements Bot {
    private final EmailAddress emailAddress;
    private final HostedRepository codeRepo;
    private final HostedRepository archiveRepo;
    private final String archiveRef;
    private final HostedRepository censusRepo;
    private final String censusRef;
    private final EmailAddress listAddress;
    private final Set<String> ignoredUsers;
    private final Set<Pattern> ignoredComments;
    private final URI listArchive;
    private final String smtpServer;
    private final WebrevStorage webrevStorage;
    private final Set<String> readyLabels;
    private final Map<String, Pattern> readyComments;
    private final Map<String, String> headers;
    private final URI issueTracker;
    private final PullRequestUpdateCache updateCache;
    private final Duration sendInterval;
    private final Duration cooldown;
    private final boolean repoInSubject;
    private final Pattern branchInSubject;
    private final Path seedStorage;
    private final CooldownQuarantine cooldownQuarantine;

    private final Logger log = Logger.getLogger("org.openjdk.skara.bots.mlbridge");

    private ZonedDateTime lastPartialUpdate;
    private ZonedDateTime lastFullUpdate;

    MailingListBridgeBot(EmailAddress from, HostedRepository repo, HostedRepository archive, String archiveRef,
                         HostedRepository censusRepo, String censusRef, EmailAddress list,
                         Set<String> ignoredUsers, Set<Pattern> ignoredComments, URI listArchive, String smtpServer,
                         HostedRepository webrevStorageRepository, String webrevStorageRef,
                         Path webrevStorageBase, URI webrevStorageBaseUri, Set<String> readyLabels,
                         Map<String, Pattern> readyComments, URI issueTracker, Map<String, String> headers,
                         Duration sendInterval, Duration cooldown, boolean repoInSubject, Pattern branchInSubject,
                         Path seedStorage) {
        emailAddress = from;
        codeRepo = repo;
        archiveRepo = archive;
        this.archiveRef = archiveRef;
        this.censusRepo = censusRepo;
        this.censusRef = censusRef;
        listAddress = list;
        this.ignoredUsers = ignoredUsers;
        this.ignoredComments = ignoredComments;
        this.listArchive = listArchive;
        this.smtpServer = smtpServer;
        this.readyLabels = readyLabels;
        this.readyComments = readyComments;
        this.headers = headers;
        this.issueTracker = issueTracker;
        this.sendInterval = sendInterval;
        this.cooldown = cooldown;
        this.repoInSubject = repoInSubject;
        this.branchInSubject = branchInSubject;
        this.seedStorage = seedStorage;

        webrevStorage = new WebrevStorage(webrevStorageRepository, webrevStorageRef, webrevStorageBase,
                                          webrevStorageBaseUri, from);
        updateCache = new PullRequestUpdateCache();
        cooldownQuarantine = new CooldownQuarantine();
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

    EmailAddress listAddress() {
        return listAddress;
    }

    Duration sendInterval() {
        return sendInterval;
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

    URI listArchive() {
        return listArchive;
    }

    String smtpServer() {
        return smtpServer;
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

    @Override
    public List<WorkItem> getPeriodicItems() {
        List<WorkItem> ret = new LinkedList<>();
        List<PullRequest> prs;

        if (lastFullUpdate == null || lastFullUpdate.isBefore(ZonedDateTime.now().minus(Duration.ofMinutes(10)))) {
            lastFullUpdate = ZonedDateTime.now();
            lastPartialUpdate = lastFullUpdate;
            log.info("Fetching all open pull requests");
            prs = codeRepo.pullRequests();
        } else {
            log.info("Fetching only pull requests updated after " + lastPartialUpdate.minus(cooldown));
            prs = codeRepo.pullRequests(lastPartialUpdate.minus(cooldown));
            lastPartialUpdate = ZonedDateTime.now();
        }

        for (var pr : prs) {
            var quarantineStatus = cooldownQuarantine.status(pr);
            if (quarantineStatus == CooldownQuarantine.Status.IN_QUARANTINE) {
                continue;
            }
            if ((quarantineStatus == CooldownQuarantine.Status.JUST_RELEASED) ||
                    (quarantineStatus == CooldownQuarantine.Status.NOT_IN_QUARANTINE && updateCache.needsUpdate(pr))) {
                ret.add(new ArchiveWorkItem(pr, this,
                                            e -> updateCache.invalidate(pr),
                                            r -> cooldownQuarantine.updateQuarantineEnd(pr, r)));
            }
        }

        return ret;
    }
}
