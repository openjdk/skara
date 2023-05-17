package org.openjdk.skara.bots.pr;

import org.openjdk.skara.bot.Bot;
import org.openjdk.skara.bot.WorkItem;
import org.openjdk.skara.forge.HostedRepository;
import org.openjdk.skara.issuetracker.Issue;
import org.openjdk.skara.issuetracker.IssuePoller;
import org.openjdk.skara.issuetracker.IssueProject;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

public class IssueBot implements Bot {
    private final IssueProject issueProject;
    private final List<HostedRepository> repositories;
    private final IssuePoller poller;

    private final Map<String, PullRequestBot> pullRequestBotMap;
    private final Map<String, List<String>> issuePRMap;

    public IssueBot(IssueProject issueProject, List<HostedRepository> repositories, Map<String, PullRequestBot> pullRequestBotMap,
                    Map<String, List<String>> issuePRMap) {
        this.issueProject = issueProject;
        this.repositories = repositories;
        this.pullRequestBotMap = pullRequestBotMap;
        this.issuePRMap = issuePRMap;
        // The PullRequestBot will initially evaluate all active PRs so there
        // is no need to look at any issues older than the start time of the bot
        // here. A padding of 10 minutes for the initial query should cover any
        // potential time difference between local and remote, as well as timing
        // issues between the first run of each bot, without the risk of
        // returning excessive amounts of Issues in the first run.
        this.poller = new IssuePoller(issueProject, Duration.ofMinutes(10)) {
            // Only query for CSR issues in this poller.
            @Override
            protected List<Issue> queryIssues(IssueProject issueProject, ZonedDateTime updatedAfter) {
                return issueProject.issues(updatedAfter).stream()
                        .filter(issue -> {
                            var issueType = issue.properties().get("issuetype");
                            return issueType != null && !"CSR".equals(issueType.asString()) && !"JEP".equals(issueType.asString());
                        })
                        .toList();
            }
        };
    }

    @Override
    public String toString() {
        return "IssueBot@" + issueProject.name();
    }

    @Override
    public List<WorkItem> getPeriodicItems() {
        var issues = poller.updatedIssues();
        var items = issues.stream()
                .map(i -> (WorkItem) new IssueWorkItem(this, i, e -> poller.retryIssue(i)))
                .toList();
        poller.lastBatchHandled();
        return items;
    }

    @Override
    public String name() {
        return PullRequestBotFactory.NAME + "-issue";
    }

    List<HostedRepository> repositories() {
        return repositories;
    }

    PullRequestBot getPRBot(String repo) {
        return pullRequestBotMap.get(repo);
    }

    Map<String, List<String>> issuePRMap() {
        return issuePRMap;
    }
}
