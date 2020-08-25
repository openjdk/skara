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
package org.openjdk.skara.vcs.hg;

import org.openjdk.skara.vcs.*;
import org.openjdk.skara.vcs.tools.*;

import java.io.IOException;
import java.util.*;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.time.format.*;
import java.nio.charset.StandardCharsets;

class HgCommitMetadata {
    private static final String delimiter = "#@!_-=&";
    private static final String hash = "{node}";
    private static final String rev = "{rev}";
    private static final String branch = "{branch}";
    private static final String parentHashes = "{p1.node} {p2.node}";
    private static final String parentRevs = "{p1.rev} {p2.rev}";
    private static final String user = "{user}";
    private static final String date = "{date|rfc3339date}";
    private static final String descLen = "{desc|count}";
    private static final String desc = "{desc}";

    public static final String TEMPLATE = String.join("\n",
                                                      delimiter,
                                                      hash,
                                                      rev,
                                                      branch,
                                                      parentHashes,
                                                      parentRevs,
                                                      user,
                                                      date,
                                                      descLen,
                                                      desc);
    public static CommitMetadata read(UnixStreamReader reader) throws IOException {
        var hash = new Hash(reader.readLine());

        reader.readLine(); // skip revision number
        reader.readLine(); // skip branch name

        var parents = new ArrayList<Hash>();
        for (var parentHash : reader.readLine().split(" ")) {
            parents.add(new Hash(parentHash));
        }
        reader.readLine(); // skip revision numbers for parents

        var author = Author.fromString(reader.readLine());

        // ext.py and hg uses slightly different time formats
        ZonedDateTime authored = null;
        var date = reader.readLine();
        try {
            // ext.py
            var formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd H:m:sZ");
            authored = ZonedDateTime.parse(date, formatter);
        } catch (DateTimeParseException e) {
            // hg's rfc3339date
            authored = ZonedDateTime.parse(date, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        }

        var messageSize = Integer.parseInt(reader.readLine());
        var messageBuffer = reader.read(messageSize);
        var message = new ArrayList<String>();
        var last = -1;
        for (var i = 0; i < messageSize; i++) {
            var offset = last + 1;
            if (messageBuffer[i] == (byte) '\n') {
                message.add(new String(messageBuffer, offset, i - offset, StandardCharsets.UTF_8));
                last = i;
            } else if (i == (messageSize - 1)) {
                // the last character wasn't newline, add the rest
                message.add(new String(messageBuffer, offset, messageSize - offset, StandardCharsets.UTF_8));
            }
        }

        return new CommitMetadata(hash, parents, author, authored, author, authored, message);
    }
}
