package org.openjdk.skara.bots.pr;

import org.openjdk.skara.forge.PullRequest;
import org.openjdk.skara.issuetracker.Comment;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

public class CheckCommand implements CommandHandler {
    @Override
    public String description() {
        return "run Jcheck again";
    }

    @Override
    public void handle(PullRequestBot bot, PullRequest pr, CensusInstance censusInstance, Path scratchPath, CommandInvocation command, List<Comment> allComments, PrintWriter reply) {
        bot.scheduleRecheckAt(pr, Instant.now());
    }

}
