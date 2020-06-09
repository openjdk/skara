package org.openjdk.skara.bots.notify.issue;

import org.openjdk.skara.bot.BotConfiguration;
import org.openjdk.skara.bots.notify.*;
import org.openjdk.skara.json.JSONObject;

import java.net.URI;

public class IssueNotifierFactory implements NotifierFactory {
    @Override
    public String name() {
        return "issue";
    }

    @Override
    public Notifier create(BotConfiguration botConfiguration, JSONObject notifierConfiguration) {
        var issueProject = botConfiguration.issueProject(notifierConfiguration.get("project").asString());
        var builder = IssueNotifier.newBuilder()
                                   .issueProject(issueProject);

        if (notifierConfiguration.contains("reviews")) {
            if (notifierConfiguration.get("reviews").contains("icon")) {
                builder.reviewIcon(URI.create(notifierConfiguration.get("reviews").get("icon").asString()));
            }
        }
        if (notifierConfiguration.contains("commits")) {
            if (notifierConfiguration.get("commits").contains("icon")) {
                builder.commitIcon(URI.create(notifierConfiguration.get("commits").get("icon").asString()));
            }
        }

        if (notifierConfiguration.contains("reviewlink")) {
            builder.reviewLink(notifierConfiguration.get("reviewlink").asBoolean());
        }
        if (notifierConfiguration.contains("commitlink")) {
            builder.commitLink(notifierConfiguration.get("commitlink").asBoolean());
        }

        return builder.build();
    }
}
