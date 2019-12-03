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
package org.openjdk.skara.email;

import java.time.ZonedDateTime;
import java.util.*;

public class EmailBuilder {
    private EmailAddress author;
    private String subject;
    private String body;
    private EmailAddress sender;
    private EmailAddress id;
    private ZonedDateTime date;

    private final List<EmailAddress> recipients = new ArrayList<>();
    private final Map<String, String> headers = new HashMap<>();

    EmailBuilder(String subject, String body) {
        this.subject = subject;
        this.body = body;

        date = ZonedDateTime.now();
    }
    EmailBuilder(EmailAddress author, String subject, String body) {
        this(subject, body);
        author(author);
    }

    public EmailBuilder reply(Email parent) {
        var references = parent.id().toString();
        if (parent.hasHeader("References")) {
            references = parent.headerValue("References") + " " + references;
        }
        header("In-Reply-To", parent.id().toString());
        header("References", references);
        return this;
    }

    public EmailBuilder author(EmailAddress author) {
        this.author = author;
        return this;
    }

    public EmailBuilder subject(String subject) {
        this.subject = subject;
        return this;
    }

    public EmailBuilder body(String body) {
        this.body = body;
        return this;
    }

    public EmailBuilder sender(EmailAddress sender) {
        this.sender = sender;
        return this;
    }

    public EmailBuilder id(EmailAddress id) {
        this.id = id;
        return this;
    }

    public EmailBuilder recipient(EmailAddress recipient) {
        recipients.add(recipient);
        return this;
    }

    public EmailBuilder recipients(List<EmailAddress> recipients) {
        this.recipients.addAll(recipients);
        return this;
    }

    public EmailBuilder header(String key, String value) {
        headers.put(key, value);
        return this;
    }

    public EmailBuilder headers(Map<String, String> headers) {
        this.headers.putAll(headers);
        return this;
    }

    public EmailBuilder replaceHeaders(Map<String, String> headers) {
        this.headers.clear();
        this.headers.putAll(headers);
        return this;
    }

    public EmailBuilder date(ZonedDateTime date) {
        this.date = date;
        return this;
    }

    public Email build() {
        if (id == null) {
            id = EmailAddress.from(UUID.randomUUID() + "@" + author.domain());
        }
        if (sender == null) {
            sender = author;
        }
        return new Email(id, date, recipients, author, sender, subject, body, headers);
    }
}
