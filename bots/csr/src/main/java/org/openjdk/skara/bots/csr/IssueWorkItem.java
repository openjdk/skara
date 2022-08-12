package org.openjdk.skara.bots.csr;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;
import java.util.stream.Stream;
import org.openjdk.skara.bot.WorkItem;
import org.openjdk.skara.forge.PullRequestUtils;
import org.openjdk.skara.issuetracker.Issue;
import org.openjdk.skara.issuetracker.Link;
import org.openjdk.skara.jbs.Backports;

/**
 * The IssueWorkItem is read-only. Its purpose is to create PullRequestWorkItems for
 * every pull request found in the Backport hierarchy associated with a CSR issue.
 * It should only be triggered when a modified CSR issue has been found.
 */
class IssueWorkItem implements WorkItem {
    private final Logger log = Logger.getLogger("org.openjdk.skara.bots.csr");

    private final CSRIssueBot bot;
    private final Issue csrIssue;

    public IssueWorkItem(CSRIssueBot bot, Issue csrIssue) {
        this.bot = bot;
        this.csrIssue = csrIssue;
    }

    @Override
    public String toString() {
        return botName() + "/IssueWorkItem@" + csrIssue.id();
    }

    @Override
    public boolean concurrentWith(WorkItem other) {
        if (!(other instanceof IssueWorkItem otherItem)) {
            return true;
        }

        return !csrIssue.project().name().equals(otherItem.csrIssue.project().name());
    }

    @Override
    public Collection<WorkItem> run(Path scratchPath) {
        var link = csrIssue.links().stream()
                .filter(l -> l.relationship().isPresent() && "csr of".equals(l.relationship().get())).findAny();
        var issue = link.flatMap(Link::issue);
        var mainIssue = issue.flatMap(Backports::findMainIssue);
        if (mainIssue.isEmpty()) {
            return List.of();
        }
        var backports = Backports.findBackports(mainIssue.get(), false);
        var ret = new ArrayList<WorkItem>();
        Stream.concat(mainIssue.stream(), backports.stream())
                .flatMap(i -> PullRequestUtils.pullRequestCommentLink(i).stream())
                .map(uri -> bot.repositories().stream()
                        .map(r -> r.parsePullRequestUrl(uri.toString()))
                        .flatMap(Optional::stream)
                        .findAny())
                .flatMap(Optional::stream)
                .map(pr -> new PullRequestWorkItem(pr.repository(), pr.id(), csrIssue.project()))
                .forEach(ret::add);
        ret.forEach(item -> log.fine("Scheduling: " + item.toString() + " due to update in " + csrIssue.id()));
        return ret;
    }

    @Override
    public String botName() {
        return CSRBotFactory.NAME;
    }

    @Override
    public String workItemName() {
        return "issue";
    }
}
