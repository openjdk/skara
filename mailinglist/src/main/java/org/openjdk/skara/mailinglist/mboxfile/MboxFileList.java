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
package org.openjdk.skara.mailinglist.mboxfile;

import org.openjdk.skara.email.*;
import org.openjdk.skara.mailinglist.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class MboxFileList implements MailingList {
    private final Path file;
    private final EmailAddress recipient;
    private final Logger log = Logger.getLogger("org.openjdk.skara.mailinglist");

    MboxFileList(Path file, EmailAddress recipient) {
        this.file = file.resolveSibling(file.getFileName() + ".mbox");
        this.recipient = recipient;
    }

    private void postNewConversation(Email mail) {
        var mboxMail = Mbox.fromMail(mail);
        if (Files.notExists(file)) {
            if (Files.notExists(file.getParent())) {
                try {
                    Files.createDirectories(file.getParent());
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
        try {
            Files.writeString(file, mboxMail, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        } catch (IOException e) {
            try {
                Files.writeString(file, mboxMail, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            } catch (IOException e1) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private void postReply(Email mail) {
        var mboxMail = Mbox.fromMail(mail);
        try {
            Files.writeString(file, mboxMail, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void post(Email email) {
        if (email.hasHeader(("In-Reply-To"))) {
            postReply(email);
        } else {
            postNewConversation(email);
        }
    }

    @Override
    public List<Conversation> conversations(Duration maxAge) {
        String mbox;
        try {
            mbox = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.info("Failed to open mbox file");
            return new LinkedList<>();
        }
        var cutoff = Instant.now().minus(maxAge);
        return Mbox.parseMbox(mbox).stream()
                .filter(email -> email.first().date().toInstant().isAfter(cutoff))
                .collect(Collectors.toList());
    }
}
