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

import org.openjdk.skara.email.*;
import org.openjdk.skara.mailinglist.*;

import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class MboxFileListReader implements MailingListReader {
    private final Path base;
    private final Collection<String> names;
    private final Logger log = Logger.getLogger("org.openjdk.skara.mailinglist");

    MboxFileListReader(Path base, Collection<String> names) {
        this.base = base;
        this.names = names;
    }

    @Override
    public List<Conversation> conversations(Duration maxAge) {
        var emails = new ArrayList<Email>();
        for (var name : names) {
            try {
                var file = base.resolve(name + ".mbox");
                var currentMbox = Files.readString(file);
                emails.addAll(Mbox.splitMbox(currentMbox, EmailAddress.from(name + "@mbox.file")));
            } catch (IOException e) {
                log.info("Failed to open mbox file");
            }
        }
        if (emails.isEmpty()) {
            return new LinkedList<>();
        }
        var cutoff = Instant.now().minus(maxAge);
        return Mbox.parseMbox(emails).stream()
                   .filter(email -> email.first().date().toInstant().isAfter(cutoff))
                   .collect(Collectors.toList());
    }
}
