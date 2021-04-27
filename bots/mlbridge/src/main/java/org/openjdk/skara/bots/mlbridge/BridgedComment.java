/*
 * Copyright (c) 2021, Oracle and/or its affiliates. All rights reserved.
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

import org.openjdk.skara.email.*;
import org.openjdk.skara.forge.PullRequest;
import org.openjdk.skara.host.HostUser;
import org.openjdk.skara.issuetracker.Comment;

import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.regex.Pattern;

public class BridgedComment {
    private final EmailAddress messageId;
    private final String body;
    private final HostUser author;
    private final ZonedDateTime created;

    private final static String bridgedMailMarker = "<!-- Bridged id (%s) -->";
    final static Pattern bridgedMailId = Pattern.compile("^<!-- Bridged id \\(([=+/\\w]+)\\) -->");
    private final static Pattern bridgedSender = Pattern.compile("Mailing list message from \\[(.*?)]\\(mailto:(\\S+)\\)");

    private BridgedComment(String body, EmailAddress messageId, HostUser author, ZonedDateTime created) {
        this.messageId = messageId;
        this.body = body;
        this.author = author;
        this.created = created;
    }

    static Optional<BridgedComment> from(Comment comment, HostUser botUser) {
        if (!comment.author().equals(botUser)) {
            return Optional.empty();
        }
        var matcher = bridgedMailId.matcher(comment.body());
        if (!matcher.find()) {
            return Optional.empty();
        }
        var id = new String(Base64.getDecoder().decode(matcher.group(1)), StandardCharsets.UTF_8);
        var senderMatcher = bridgedSender.matcher(comment.body());
        if (!senderMatcher.find()) {
            return Optional.empty();
        }
        var author = HostUser.builder()
                             .id("bridged")
                             .username("bridged")
                             .fullName(senderMatcher.group(1))
                             .email(senderMatcher.group(2))
                             .build();
        var headerEnd = comment.body().indexOf("\n\n", senderMatcher.end());
        var bridgedBody = comment.body().substring(headerEnd).strip();
        return Optional.of(new BridgedComment(bridgedBody, EmailAddress.from(id), author, comment.createdAt()));
    }

    static BridgedComment post(PullRequest pr, Email email) {
        var marker = String.format(bridgedMailMarker,
                                   Base64.getEncoder().encodeToString(email.id().address().getBytes(StandardCharsets.UTF_8)));

        var filteredEmail = QuoteFilter.stripLinkBlock(email.body(), pr.webUrl());
        var body = marker + "\n" +
                "*Mailing list message from [" + email.author().fullName().orElse(email.author().localPart()) +
                "](mailto:" + email.author().address() + ") on [" + email.sender().localPart() +
                "](mailto:" + email.sender().address() + "):*\n\n" +
                TextToMarkdown.escapeFormatting(filteredEmail);
        if (body.length() > 64000) {
            body = body.substring(0, 64000) + "...\n\n" + "" +
                    "This message was too large to bridge in full, and has been truncated. " +
                    "Please check the mailing list archive to see the full text.";
        }
        var comment = pr.addComment(body);
        return BridgedComment.from(comment, pr.repository().forge().currentUser()).orElseThrow();
    }

    public EmailAddress messageId() {
        return messageId;
    }

    public String body() {
        return body;
    }

    public HostUser author() {
        return author;
    }

    public ZonedDateTime created() {
        return created;
    }

    public static boolean isBridgedUser(HostUser user) {
        // All supported platforms use numerical IDs, so this special one can not cause conflicts
        return user.id().equals("bridged");
    }
}
