/*
 * Copyright (c) 2026, Oracle and/or its affiliates. All rights reserved.
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

import java.time.format.DateTimeFormatter;
import java.util.stream.Collectors;

public class EmailMessage {
    public static String toRfc822(Email email) {
        var recipientList = email.recipients().stream()
                .map(EmailAddress::toString)
                .map(MimeText::encode)
                .collect(Collectors.joining(", "));
        var ret = new StringBuilder();
        ret.append("From: ").append(MimeText.encode(email.author().toString())).append("\r\n");
        ret.append("Message-Id: ").append(email.id()).append("\r\n");
        ret.append("Date: ").append(email.date().format(DateTimeFormatter.RFC_1123_DATE_TIME)).append("\r\n");
        ret.append("Sender: ").append(MimeText.encode(email.sender().toString())).append("\r\n");
        ret.append("To: ").append(recipientList).append("\r\n");
        for (var header : email.headers()) {
            ret.append(header).append(": ").append(MimeText.encode(email.headerValue(header))).append("\r\n");
        }
        ret.append("Subject: ").append(MimeText.encode(email.subject())).append("\r\n");
        ret.append("Content-type: text/plain; charset=utf-8").append("\r\n");
        ret.append("\r\n");
        ret.append(email.body().replace("\r\n", "\n").replace("\n", "\r\n"));
        return ret.toString();
    }
}
