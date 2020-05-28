package org.openjdk.skara.bots.notify.issue;

import org.openjdk.skara.bot.BotConfiguration;
import org.openjdk.skara.bots.notify.*;
import org.openjdk.skara.json.JSONObject;

import java.net.URI;

public class IssueUpdaterFactory implements NotifierFactory {
    @Override
    public String name() {
        return "issue";
    }

    @Override
    public Notifier create(BotConfiguration botConfiguration, JSONObject notifierConfiguration) {
        var issueProject = botConfiguration.issueProject(notifierConfiguration.get("project").asString());
        var issueUpdaterBuilder = IssueUpdater.newBuilder()
                .issueProject(issueProject);

        if (notifierConfiguration.contains("reviews")) {
            if (notifierConfiguration.get("reviews").contains("icon")) {
                issueUpdaterBuilder.reviewIcon(URI.create(notifierConfiguration.get("reviews").get("icon").asString()));
            }
        }
        if (notifierConfiguration.contains("commits")) {
            if (notifierConfiguration.get("commits").contains("icon")) {
                issueUpdaterBuilder.commitIcon(URI.create(notifierConfiguration.get("commits").get("icon").asString()));
            }
        }

        if (notifierConfiguration.contains("reviewlink")) {
            issueUpdaterBuilder.reviewLink(notifierConfiguration.get("reviewlink").asBoolean());
        }
        if (notifierConfiguration.contains("commitlink")) {
            issueUpdaterBuilder.commitLink(notifierConfiguration.get("commitlink").asBoolean());
        }

        return issueUpdaterBuilder.build();
    }
}
