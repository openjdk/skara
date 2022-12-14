/*
 * Copyright (c) 2021, 2022, Oracle and/or its affiliates. All rights reserved.
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
import org.openjdk.skara.issuetracker.Label;

import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;

/**
 * This WorkItem runs once when the bots starts up to update the repository
 * with all mailing list labels configured for it.
 */
public class LabelsUpdaterWorkItem implements WorkItem {
    private static final Logger log = Logger.getLogger(LabelsUpdaterWorkItem.class.getName());

    private final MailingListBridgeBot bot;

    public LabelsUpdaterWorkItem(MailingListBridgeBot bot) {
        this.bot = bot;
    }

    public MailingListBridgeBot bot() {
        return bot;
    }

    @Override
    public boolean concurrentWith(WorkItem other) {
        if (!(other instanceof LabelsUpdaterWorkItem otherItem)) {
            return true;
        }
        if (!bot.equals(otherItem.bot)) {
            return true;
        }
        return false;
    }

    @Override
    public Collection<WorkItem> run(Path scratchPath) {
        if (bot.labelsUpdated()) {
            return List.of();
        }

        var existingLabelsMap = new HashMap<String, Label>();
        bot.codeRepo().labels().forEach(l -> existingLabelsMap.put(l.name(), l));

        var configuredLabels = bot.lists().stream()
                .flatMap(configuration -> configuration.labels().stream()
                        .map(labelName -> new Label(labelName, configuration.list().address())))
                .toList();

        for (Label configuredLabel : configuredLabels) {
            var existingLabel = existingLabelsMap.get(configuredLabel.name());
            if (existingLabel == null) {
                log.info("Adding label: " + configuredLabel.name() + " to repo: " + bot.codeRepo().name());
                bot.codeRepo().addLabel(configuredLabel);
            } else if (!existingLabel.description().equals(configuredLabel.description())) {
                log.info("Updating label: " + configuredLabel.name() + " with description: "
                        + configuredLabel.description() + " for repo: " + bot.codeRepo().name());
                bot.codeRepo().updateLabel(configuredLabel);
            }
        }

        log.fine("Done updating labels for: " + bot.codeRepo().name());
        bot.setLabelsUpdated(true);
        return List.of();
    }

    @Override
    public String botName() {
        return MailingListBridgeBotFactory.NAME;
    }

    @Override
    public String workItemName() {
        return "labels-updater";
    }

    @Override
    public String toString() {
        return "LabelsUpdaterWorkItem@" + bot.codeRepo().name();
    }
}
