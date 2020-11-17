package org.openjdk.skara.bots.pr;

import org.openjdk.skara.forge.HostedRepositoryPool;
import org.openjdk.skara.forge.PullRequest;
import org.openjdk.skara.forge.PullRequestUtils;
import org.openjdk.skara.issuetracker.Comment;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;

public class CheckCommand implements CommandHandler {
    @Override
    public String description() {
        return "run Jcheck again";
    }

    @Override
    public void handle(PullRequestBot bot, PullRequest pr, CensusInstance censusInstance, Path scratchPath, CommandInvocation command, List<Comment> allComments, PrintWriter reply) {
        try {
            var workItem = new CheckWorkItem(bot, pr, e -> {});

            var path = scratchPath.resolve("check").resolve(pr.repository().name());
            var seedPath = bot.seedStorage().orElse(scratchPath.resolve("seeds"));
            var hostedRepositoryPool = new HostedRepositoryPool(seedPath);
            var localRepo = PullRequestUtils.materialize(hostedRepositoryPool, pr, path);

            var allReviews = pr.reviews();
            var activeReviews = CheckablePullRequest.filterActiveReviews(allReviews);
            var activeLabels = new HashSet<>(pr.labels());

            CheckRun.execute(workItem, pr, localRepo, allComments, allReviews, activeReviews, activeLabels, censusInstance, bot.ignoreStaleReviews());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

}
