/*
 * Copyright (c) 2019, 2024, Oracle and/or its affiliates. All rights reserved.
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

import org.openjdk.skara.email.Email;
import org.openjdk.skara.mailinglist.*;

import java.io.*;
import java.nio.file.*;
import java.util.Arrays;
import java.util.stream.Collectors;

public class MboxFileListServer implements MailingListServer {
    Path base;

    public MboxFileListServer(Path base) {
        this.base = base;
    }

    private void postNewConversation(Path file, Email mail) {
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
            Files.writeString(file, mboxMail, StandardOpenOption.APPEND);
        } catch (IOException e) {
            try {
                Files.writeString(file, mboxMail, StandardOpenOption.CREATE_NEW);
            } catch (IOException e1) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private void postReply(Path file, Email mail) {
        var mboxMail = Mbox.fromMail(mail);
        try {
            Files.writeString(file, mboxMail, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void post(Email email) {
        var recipientList = email.recipients().stream()
                                 .map(e -> base.resolve(e.localPart() + ".mbox"))
                                 .collect(Collectors.toList());

        if (email.hasHeader(("In-Reply-To"))) {
            recipientList.forEach(list -> postReply(list, email));
        } else {
            recipientList.forEach(list -> postNewConversation(list, email));
        }
    }

    @Override
    public MailingListReader getListReader(String... listNames) {
        return new MboxFileListReader(base, Arrays.asList(listNames));
    }
}
