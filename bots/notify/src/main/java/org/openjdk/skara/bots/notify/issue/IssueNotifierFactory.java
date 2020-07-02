package org.openjdk.skara.bots.notify.issue;

import org.openjdk.skara.bot.BotConfiguration;
import org.openjdk.skara.bots.notify.*;
import org.openjdk.skara.host.Credential;
import org.openjdk.skara.json.JSONObject;
import org.openjdk.skara.network.URIBuilder;

import java.net.URI;
import java.util.stream.Collectors;

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

        if (notifierConfiguration.contains("fixversions")) {
            builder.setFixVersion(true);
            builder.fixVersions(notifierConfiguration.get("fixversions").fields().stream()
                                                      .collect(Collectors.toMap(JSONObject.Field::name,
                                                                                f -> f.value().asString())));
        }

        if (notifierConfiguration.contains("vault")) {
            var vaultConfiguration = notifierConfiguration.get("vault").asObject();
            var credential = new Credential(vaultConfiguration.get("username").asString(), vaultConfiguration.get("password").asString());

            if (credential.username().startsWith("https://")) {
                var vaultUrl = URIBuilder.base(credential.username()).build();
                var jbsVault = new JbsVault(vaultUrl, credential.password());
                builder.vault(jbsVault);
            } else {
                throw new RuntimeException("basic authentication not implemented yet");
            }
        }

        if (notifierConfiguration.contains("security")) {
            builder.securityLevel(notifierConfiguration.get("security").asString());
        }

        if (notifierConfiguration.contains("pronly")) {
            builder.prOnly(notifierConfiguration.get("pronly").asBoolean());
        }

        return builder.build();
    }
}
