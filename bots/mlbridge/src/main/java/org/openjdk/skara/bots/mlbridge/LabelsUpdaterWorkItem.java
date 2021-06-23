package org.openjdk.skara.bots.mlbridge;

import org.openjdk.skara.bot.WorkItem;
import org.openjdk.skara.issuetracker.Label;

import java.nio.file.Path;
import java.util.*;

/**
 * This WorkItem runs once when the bots starts up to update the repository
 * with all mailing list labels configured for it.
 */
public class LabelsUpdaterWorkItem implements WorkItem {

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
                        .map(labelName -> new Label(labelName, configuration.list().toString())))
                .toList();

        for (Label configuredLabel : configuredLabels) {
            var existingLabel = existingLabelsMap.get(configuredLabel.name());
            if (existingLabel == null) {
                bot.codeRepo().addLabel(configuredLabel);
            } else if (!existingLabel.description().equals(configuredLabel.description())) {
                bot.codeRepo().updateLabel(configuredLabel);
            }
        }

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
}
