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
package org.openjdk.skara.bots.mlbridge;

import org.openjdk.skara.email.EmailAddress;
import org.openjdk.skara.forge.HostedRepository;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;

public class MailingListBridgeBotBuilder {
    private EmailAddress from;
    private HostedRepository repo;
    private HostedRepository archive;
    private String archiveRef = "master";
    private HostedRepository censusRepo;
    private String censusRef = "master";
    private List<MailingListConfiguration> lists;
    private Set<String> ignoredUsers = Set.of();
    private Set<Pattern> ignoredComments = Set.of();
    private URI listArchive;
    private String smtpServer;
    private HostedRepository webrevStorageHTMLRepository;
    private HostedRepository webrevStorageJSONRepository;
    private String webrevStorageRef;
    private Path webrevStorageBase;
    private URI webrevStorageBaseUri;
    private boolean webrevGenerateHTML = true;
    private boolean webrevGenerateJSON = false;
    private Set<String> readyLabels = Set.of();
    private Map<String, Pattern> readyComments = Map.of();
    private URI issueTracker;
    private Map<String, String> headers = Map.of();
    private Duration sendInterval = Duration.ZERO;
    private Duration cooldown = Duration.ZERO;
    private boolean repoInSubject = false;
    private Pattern branchInSubject = Pattern.compile("a^"); // Does not match anything
    private Path seedStorage = null;

    MailingListBridgeBotBuilder() {
    }

    public MailingListBridgeBotBuilder from(EmailAddress from) {
        this.from = from;
        return this;
    }

    public MailingListBridgeBotBuilder repo(HostedRepository repo) {
        this.repo = repo;
        return this;
    }

    public MailingListBridgeBotBuilder archive(HostedRepository archive) {
        this.archive = archive;
        return this;
    }

    public MailingListBridgeBotBuilder archiveRef(String archiveRef) {
        this.archiveRef = archiveRef;
        return this;
    }

    public MailingListBridgeBotBuilder censusRepo(HostedRepository censusRepo) {
        this.censusRepo = censusRepo;
        return this;
    }

    public MailingListBridgeBotBuilder censusRef(String censusRef) {
        this.censusRef = censusRef;
        return this;
    }

    public MailingListBridgeBotBuilder lists(List<MailingListConfiguration> lists) {
        this.lists = lists;
        return this;
    }

    public MailingListBridgeBotBuilder ignoredUsers(Set<String> ignoredUsers) {
        this.ignoredUsers = ignoredUsers;
        return this;
    }

    public MailingListBridgeBotBuilder ignoredComments(Set<Pattern> ignoredComments) {
        this.ignoredComments = ignoredComments;
        return this;
    }

    public MailingListBridgeBotBuilder listArchive(URI listArchive) {
        this.listArchive = listArchive;
        return this;
    }

    public MailingListBridgeBotBuilder smtpServer(String smtpServer) {
        this.smtpServer = smtpServer;
        return this;
    }

    public MailingListBridgeBotBuilder webrevStorageHTMLRepository(HostedRepository webrevStorageHTMLRepository) {
        this.webrevStorageHTMLRepository = webrevStorageHTMLRepository;
        return this;
    }

    public MailingListBridgeBotBuilder webrevStorageJSONRepository(HostedRepository webrevStorageJSONRepository) {
        this.webrevStorageJSONRepository = webrevStorageJSONRepository;
        return this;
    }

    public MailingListBridgeBotBuilder webrevStorageRef(String webrevStorageRef) {
        this.webrevStorageRef = webrevStorageRef;
        return this;
    }

    public MailingListBridgeBotBuilder webrevStorageBase(Path webrevStorageBase) {
        this.webrevStorageBase = webrevStorageBase;
        return this;
    }

    public MailingListBridgeBotBuilder webrevStorageBaseUri(URI webrevStorageBaseUri) {
        this.webrevStorageBaseUri = webrevStorageBaseUri;
        return this;
    }

    public MailingListBridgeBotBuilder webrevGenerateHTML(boolean webrevGenerateHTML) {
        this.webrevGenerateHTML = webrevGenerateHTML;
        return this;
    }

    public MailingListBridgeBotBuilder webrevGenerateJSON(boolean webrevGenerateJSON) {
        this.webrevGenerateJSON = webrevGenerateJSON;
        return this;
    }

    public MailingListBridgeBotBuilder readyLabels(Set<String> readyLabels) {
        this.readyLabels = readyLabels;
        return this;
    }

    public MailingListBridgeBotBuilder readyComments(Map<String, Pattern> readyComments) {
        this.readyComments = readyComments;
        return this;
    }

    public MailingListBridgeBotBuilder issueTracker(URI issueTracker) {
        this.issueTracker = issueTracker;
        return this;
    }

    public MailingListBridgeBotBuilder headers(Map<String, String> headers) {
        this.headers = headers;
        return this;
    }

    public MailingListBridgeBotBuilder sendInterval(Duration sendInterval) {
        this.sendInterval = sendInterval;
        return this;
    }

    public MailingListBridgeBotBuilder cooldown(Duration cooldown) {
        this.cooldown = cooldown;
        return this;
    }

    public MailingListBridgeBotBuilder repoInSubject(boolean repoInSubject) {
        this.repoInSubject = repoInSubject;
        return this;
    }

    public MailingListBridgeBotBuilder branchInSubject(Pattern branchInSubject) {
        this.branchInSubject = branchInSubject;
        return this;
    }

    public MailingListBridgeBotBuilder seedStorage(Path seedStorage) {
        this.seedStorage = seedStorage;
        return this;
    }

    public MailingListBridgeBot build() {
        return new MailingListBridgeBot(from, repo, archive, archiveRef, censusRepo, censusRef, lists,
                                        ignoredUsers, ignoredComments, listArchive, smtpServer,
                                        webrevStorageHTMLRepository, webrevStorageJSONRepository, webrevStorageRef,
                                        webrevStorageBase, webrevStorageBaseUri, webrevGenerateHTML, webrevGenerateJSON,
                                        readyLabels, readyComments, issueTracker, headers, sendInterval,
                                        cooldown, repoInSubject, branchInSubject, seedStorage);
    }
}
