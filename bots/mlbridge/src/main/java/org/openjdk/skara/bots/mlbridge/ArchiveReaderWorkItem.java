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
package org.openjdk.skara.bots.mlbridge;

import org.openjdk.skara.bot.WorkItem;
import org.openjdk.skara.mailinglist.MailingListReader;

import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

public class ArchiveReaderWorkItem implements WorkItem {
    private final MailingListArchiveReaderBot bot;
    private final MailingListReader list;

    ArchiveReaderWorkItem(MailingListArchiveReaderBot bot, MailingListReader list) {
        this.bot = bot;
        this.list = list;
    }

    @Override
    public String toString() {
        return "ArchiveReaderWorkItem@" + bot.repository().name();
    }

    @Override
    public boolean concurrentWith(WorkItem other) {
        if (!(other instanceof ArchiveReaderWorkItem otherItem)) {
            return true;
        }
        if (!list.equals(otherItem.list)) {
            return true;
        }
        return false;
    }

    /**
     * An ArchiveReaderWorkItem can't run concurrently with another item that shares the same
     * MailingListReader, but it only replaces an item that acts on the same repository.
     */
    @Override
    public boolean replaces(WorkItem other) {
        return !concurrentWith(other)
                && (other instanceof ArchiveReaderWorkItem archiveReaderWorkItem)
                && bot.repository().name().equals(archiveReaderWorkItem.bot.repository().name());
    }

    @Override
    public Collection<WorkItem> run(Path scratchPath) {
        // Give the bot a chance to act on all found messages
        var conversations = list.conversations(Duration.ofDays(365));
        for (var conversation : conversations) {
            bot.inspect(conversation);
        }
        return List.of();
    }

    @Override
    public String botName() {
        return MailingListBridgeBotFactory.NAME;
    }

    @Override
    public String workItemName() {
        return "archive-reader";
    }
}
