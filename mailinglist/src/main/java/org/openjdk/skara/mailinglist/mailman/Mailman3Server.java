/*
 * Copyright (c) 2025, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.mailinglist.mailman;

import java.net.URI;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Arrays;
import org.openjdk.skara.mailinglist.MailingListReader;

public class Mailman3Server extends MailmanServer {
    private final ZonedDateTime startTime;

    public Mailman3Server(URI archive, String smtpServer, Duration sendInterval, ZonedDateTime startTime) {
        super(archive, smtpServer, sendInterval, false);
        this.startTime = startTime;
    }

    public Mailman3Server(URI archive, String smtpServer, Duration sendInterval) {
        this(archive, smtpServer, sendInterval, ZonedDateTime.now());
    }

    URI getArchiveUri() {
        return archive;
    }

    @Override
    public MailingListReader getListReader(String... listNames) {
        return new Mailman3ListReader(this, Arrays.asList(listNames), startTime);
    }
}
