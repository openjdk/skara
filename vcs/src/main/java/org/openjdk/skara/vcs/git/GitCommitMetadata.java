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
package org.openjdk.skara.vcs.git;

import org.openjdk.skara.vcs.*;
import org.openjdk.skara.vcs.tools.*;

import java.io.IOException;
import java.util.*;
import java.time.*;
import java.time.format.*;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;

class GitCommitMetadata {
    private static final Logger log = Logger.getLogger("org.openjdk.skara.vcs.git");

    private static final String hashFormat = "%H";
    private static final String parentsFormat = "%P";
    private static final String authorNameFormat = "%an";
    private static final String authorEmailFormat = "%ae";
    private static final String committerNameFormat = "%cn";
    private static final String committerEmailFormat = "%ce";
    private static final String timestampFormat = "%aI";

    private static final String messageDelimiter = "=@=@=@=@=@";
    private static final String messageFormat = "%B" + messageDelimiter;

    public static final String FORMAT = String.join("%n",
                                                    hashFormat,
                                                    parentsFormat,
                                                    authorNameFormat,
                                                    authorEmailFormat,
                                                    committerNameFormat,
                                                    committerEmailFormat,
                                                    timestampFormat,
                                                    messageFormat);

    public static CommitMetadata read(UnixStreamReader reader) throws IOException {
        var hash = new Hash(reader.readLine());
        log.fine("Parsing: " + hash.hex());

        var parentHashes = reader.readLine();
        if (parentHashes.equals("")) {
            parentHashes = Hash.zero().hex();
        }
        var parents = new ArrayList<Hash>();
        for (var parentHash : parentHashes.split(" ")) {
            parents.add(new Hash(parentHash));
        }

        var authorName = reader.readLine();
        log.finer("authorName: " + authorName);
        var authorEmail = reader.readLine();
        log.finer("authorEmail: " + authorEmail);
        var author = new Author(authorName, authorEmail);

        var committerName = reader.readLine();
        log.finer("committerName: " + committerName);
        var committerEmail = reader.readLine();
        log.finer("committerEmail " + committerName);
        var committer = new Author(committerName, committerEmail);

        var formatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
        var date = ZonedDateTime.parse(reader.readLine(), formatter);

        var message = new ArrayList<String>();
        var line = reader.readLine();
        while (!line.endsWith(messageDelimiter)) {
            message.add(line);
            line = reader.readLine();
        }
        // the last commit message doesn't have to end with '\n'
        if (!line.equals(messageDelimiter)) {
            var prefix = line.substring(0, line.length() - messageDelimiter.length());
            message.add(prefix);
        }

        return new CommitMetadata(hash, parents, author, committer, date, message);
    }
}
