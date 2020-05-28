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
package org.openjdk.skara.bots.notify.slack;

import org.openjdk.skara.bots.notify.*;
import org.openjdk.skara.forge.HostedRepository;
import org.openjdk.skara.forge.PullRequest;
import org.openjdk.skara.json.*;
import org.openjdk.skara.vcs.*;
import org.openjdk.skara.network.*;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.List;
import java.time.format.DateTimeFormatter;

public class SlackUpdater implements RepositoryUpdateConsumer, PullRequestUpdateConsumer {
    private final RestRequest prWebhook;
    private final RestRequest commitWebhook;
    private final String username;

    public SlackUpdater(URI prWebhook, URI commitWebhook, String username) {
        this.prWebhook = prWebhook != null ? new RestRequest(prWebhook) : null;
        this.commitWebhook = commitWebhook != null ? new RestRequest(commitWebhook) : null;
        this.username = username;
    }

    @Override
    public void handleNewPullRequest(PullRequest pr) {
        if (prWebhook == null) {
            return;
        }

        try {
            var query = JSON.object();
            query.put("text", pr.nonTransformedWebUrl().toString());
            if (username != null && !username.isEmpty()) {
                query.put("username", username);
            }
            prWebhook.post("").body(query).executeUnparsed();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void handleCommits(HostedRepository repository,
                              Repository localRepository,
                              List<Commit> commits,
                              Branch branch) throws NonRetriableException {
        if (commitWebhook == null) {
            return;
        }

        try {
            for (var commit : commits) {
                var query = JSON.object();
                if (username != null && !username.isEmpty()) {
                    query.put("username", username);
                }
                var title = commit.message().get(0);
                query.put("text", branch.name() + ": " + commit.hash().abbreviate() + ": " + title + "\n" +
                                  "Author: " + commit.author().name() + "\n" +
                                  "Committer: " + commit.author().name() + "\n" +
                                  "Date: " + commit.date().format(DateTimeFormatter.RFC_1123_DATE_TIME) + "\n");

                var attachment = JSON.object();
                attachment.put("fallback", "Link to commit");
                attachment.put("color", "#cc0e31");
                attachment.put("title", "View on " + repository.forge().name());
                attachment.put("title_link", repository.webUrl(commit.hash()).toString());
                var attachments = JSON.array();
                attachments.add(attachment);
                query.put("attachments", attachments);
                commitWebhook.post("").body(query).executeUnparsed();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public String name() {
        return "slack";
    }
}
