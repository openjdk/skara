package org.openjdk.skara.forge;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.openjdk.skara.json.JSON;
import org.openjdk.skara.test.CheckableRepository;
import org.openjdk.skara.test.HostCredentials;
import org.openjdk.skara.test.TemporaryDirectory;

public class PullRequestUtilsTests {

    @Test
    void pullRequestLinkComment(TestInfo testInfo) throws IOException {
        try (var credentials = new HostCredentials(testInfo);
             var tempFolder = new TemporaryDirectory()) {
            var repo = credentials.getHostedRepository();
            var repoFolder = tempFolder.path().resolve("repo");
            var localRepo = CheckableRepository.init(repoFolder, repo.repositoryType());
            credentials.commitLock(localRepo);
            localRepo.pushAll(repo.url());

            var issueProject = credentials.getIssueProject();
            var issue = issueProject.createIssue("This is an issue", List.of("Indeed"), Map.of("issuetype", JSON.of("Enhancement")));
            var editHash = CheckableRepository.appendAndCommit(localRepo, "Another line", issue.id() + ": Fix that issue");
            localRepo.push(editHash, repo.url(), "master");
            var pr1 = credentials.createPullRequest(repo, "master", "master", issue.id() + ": Fix that issue");

            {
                assertEquals(0, issue.comments().size());

                PullRequestUtils.postPullRequestLinkComment(issue, pr1);
                assertEquals(1, issue.comments().size());

                var prLinks = PullRequestUtils.pullRequestCommentLink(issue);
                assertEquals(pr1.webUrl(), prLinks.get(0));

                PullRequestUtils.removePullRequestLinkComment(issue, pr1);
                assertEquals(0, issue.comments().size());
            }
            {
                var pr2 = credentials.createPullRequest(repo, "master", "master", issue.id() + ": Fix that issue");

                PullRequestUtils.postPullRequestLinkComment(issue, pr1);
                PullRequestUtils.postPullRequestLinkComment(issue, pr2);
                assertEquals(2, issue.comments().size());

                var prLinks = PullRequestUtils.pullRequestCommentLink(issue);
                assertEquals(pr1.webUrl(), prLinks.get(0));
                assertEquals(pr2.webUrl(), prLinks.get(1));

                PullRequestUtils.removePullRequestLinkComment(issue, pr1);
                assertEquals(1, issue.comments().size());

                prLinks = PullRequestUtils.pullRequestCommentLink(issue);
                assertEquals(pr2.webUrl(), prLinks.get(0));
            }
        }
    }
}
