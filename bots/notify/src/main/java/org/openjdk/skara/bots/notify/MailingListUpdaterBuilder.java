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
package org.openjdk.skara.bots.notify;

import org.openjdk.skara.email.EmailAddress;
import org.openjdk.skara.mailinglist.MailingList;

import java.util.Map;
import java.util.regex.Pattern;

public class MailingListUpdaterBuilder {
    private MailingList list;
    private EmailAddress recipient;
    private EmailAddress sender;
    private EmailAddress author = null;
    private boolean includeBranch = false;
    private boolean reportNewTags = true;
    private boolean reportNewBranches = true;
    private boolean reportNewBuilds = true;
    private MailingListUpdater.Mode mode = MailingListUpdater.Mode.ALL;
    private Map<String, String> headers = Map.of();
    private Pattern allowedAuthorDomains = Pattern.compile(".*");
    private boolean repoInSubject = false;
    private Pattern branchInSubject = Pattern.compile("a^"); // Does not match anything

    public MailingListUpdaterBuilder list(MailingList list) {
        this.list = list;
        return this;
    }

    public MailingListUpdaterBuilder recipient(EmailAddress recipient) {
        this.recipient = recipient;
        return this;
    }

    public MailingListUpdaterBuilder sender(EmailAddress sender) {
        this.sender = sender;
        return this;
    }

    public MailingListUpdaterBuilder author(EmailAddress author) {
        this.author = author;
        return this;
    }

    public MailingListUpdaterBuilder includeBranch(boolean includeBranch) {
        this.includeBranch = includeBranch;
        return this;
    }

    public MailingListUpdaterBuilder reportNewTags(boolean reportNewTags) {
        this.reportNewTags = reportNewTags;
        return this;
    }

    public MailingListUpdaterBuilder reportNewBranches(boolean reportNewBranches) {
        this.reportNewBranches = reportNewBranches;
        return this;
    }

    public MailingListUpdaterBuilder reportNewBuilds(boolean reportNewBuilds) {
        this.reportNewBuilds = reportNewBuilds;
        return this;
    }

    public MailingListUpdaterBuilder mode(MailingListUpdater.Mode mode) {
        this.mode = mode;
        return this;
    }

    public MailingListUpdaterBuilder headers(Map<String, String> headers) {
        this.headers = headers;
        return this;
    }

    public MailingListUpdaterBuilder allowedAuthorDomains(Pattern allowedAuthorDomains) {
        this.allowedAuthorDomains = allowedAuthorDomains;
        return this;
    }

    public MailingListUpdater build() {
        return new MailingListUpdater(list, recipient, sender, author, includeBranch, reportNewTags, reportNewBranches,
                                      reportNewBuilds, mode, headers, allowedAuthorDomains);
    }
}