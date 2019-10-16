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

import org.openjdk.skara.bot.WorkItem;
import org.openjdk.skara.email.*;
import org.openjdk.skara.forge.PullRequest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Pattern;

public class CommentPosterWorkItem implements WorkItem {
    private final PullRequest pr;
    private final List<Email> newMessages;
    private final Logger log = Logger.getLogger("org.openjdk.skara.bots.mlbridge");

    private final String bridgedMailMarker = "<!-- Bridged id (%s) -->";
    private final Pattern bridgedMailId = Pattern.compile("^<!-- Bridged id \\(([=\\w]+)\\) -->");

    CommentPosterWorkItem(PullRequest pr, List<Email> newMessages) {
        this.pr = pr;
        this.newMessages = newMessages;
    }

    @Override
    public String toString() {
        return "CommentPosterWorkItem@" + pr.toString();
    }

    @Override
    public boolean concurrentWith(WorkItem other) {
        if (!(other instanceof CommentPosterWorkItem)) {
            return true;
        }
        CommentPosterWorkItem otherItem = (CommentPosterWorkItem) other;
        if (!pr.equals(otherItem.pr)) {
            return true;
        }
        return false;
    }

    private void postNewMessage(Email email) {
        var marker = String.format(bridgedMailMarker,
                                 Base64.getEncoder().encodeToString(email.id().address().getBytes(StandardCharsets.UTF_8)));
        var body = marker + "\n" +
                "Mailing list message from " + email.author().toString() + "\n\n" +
                email.body();
        pr.addComment(body);
    }

    @Override
    public void run(Path scratchPath) {
        var comments = pr.comments();

        var alreadyBridged = new HashSet<EmailAddress>();
        for (var comment : comments) {
            if (!comment.author().equals(pr.repository().forge().currentUser())) {
                continue;
            }
            var matcher = bridgedMailId.matcher(comment.body());
            if (!matcher.find()) {
                continue;
            }
            var id = new String(Base64.getDecoder().decode(matcher.group(1)), StandardCharsets.UTF_8);
            alreadyBridged.add(EmailAddress.from(id));
        }

        for (var message : newMessages) {
            if (alreadyBridged.contains(message.id())) {
                log.fine("Message from " + message.author() + " to " + pr + " has already been bridged - skipping!");
                continue;
            }

            log.info("Bridging new message from " + message.author() + " to " + pr);
            postNewMessage(message);
        }
    }
}
