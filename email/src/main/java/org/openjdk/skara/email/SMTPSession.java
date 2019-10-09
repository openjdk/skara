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

import java.io.*;
import java.time.*;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Pattern;

public class SMTPSession {
    private final static Logger log = Logger.getLogger("org.openjdk.skara.email");;
    private final BufferedReader in;
    private final BufferedWriter out;
    private final Instant timeout;

    public SMTPSession(InputStreamReader in, OutputStreamWriter out, Duration timeout) {
        this.in = new BufferedReader(in);
        this.out = new BufferedWriter(out);
        this.timeout = Instant.now().plus(timeout);
    }

    void waitForPattern(Pattern expectedReply) throws IOException {
        while (Instant.now().isBefore(timeout)) {
            while (!in.ready()) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException ignored) {
                }
            }
            var line = in.readLine();
            var matcher = expectedReply.matcher(line);
            log.fine("< " + line);
            if (matcher.matches()) {
                return;
            }
        }
        throw new RuntimeException("Timeout waiting for pattern: " + expectedReply);
    }

    public List<String> readLinesUntil(Pattern end) throws IOException {
        var ret = new ArrayList<String>();
        while (Instant.now().isBefore(timeout)) {
            while (!in.ready()) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException ignored) {
                }
            }
            var line = in.readLine();
            var matcher = end.matcher(line);
            log.fine("< " + line);
            if (matcher.matches()) {
                return ret;
            }
            ret.add(line);
        }
        throw new RuntimeException("Timeout reading response lines: " + end);
    }

    public void sendCommand(String command, Pattern expectedReply) throws IOException {
        log.fine("> " + command);
        out.write(command + "\n");
        out.flush();

        if (expectedReply != null) {
            waitForPattern(expectedReply);
        }
    }

    public void sendCommand(String command) throws IOException {
        sendCommand(command, null);
    }
}
