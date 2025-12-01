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
package org.openjdk.skara.mailinglist.mailman;

import org.openjdk.skara.email.*;
import org.openjdk.skara.mailinglist.*;

import java.io.*;
import java.net.URI;
import java.time.*;

public abstract class MailmanServer implements MailingListServer {
    protected final URI archive;
    private final String smtpServer;
    private final Duration sendInterval;
    protected final boolean useEtag;
    private volatile Instant lastSend;

    protected MailmanServer(URI archive, String smtpServer, Duration sendInterval, boolean useEtag) {
        this.archive = archive;
        this.smtpServer = smtpServer;
        this.sendInterval = sendInterval;
        this.useEtag = useEtag;
        lastSend = Instant.EPOCH;
    }

    void sendMessage(Email message) {
        while (lastSend.plus(sendInterval).isAfter(Instant.now())) {
            try {
                Thread.sleep(sendInterval.dividedBy(10));
            } catch (InterruptedException ignored) {
            }
        }
        lastSend = Instant.now();
        try {
            SMTP.send(smtpServer, message);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void post(Email email) {
        sendMessage(email);
    }

    public URI archive() {
        return archive;
    }
}
