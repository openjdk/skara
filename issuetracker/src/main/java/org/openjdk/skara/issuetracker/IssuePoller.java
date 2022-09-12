package org.openjdk.skara.issuetracker;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class IssuePoller {

    private static final Logger log = Logger.getLogger(IssuePoller.class.getName());

    private final IssueProject issueProject;
    private final Duration timeStampQueryPrecision;
    private final ZonedDateTime initialUpdatedAt;
    private final Map<String, Issue> retryMap = new HashMap<>();

    private record QueryResult(Map<String, Issue> issues, ZonedDateTime maxUpdatedAt,
                               Instant afterQuery, List<Issue> result) {}
    private QueryResult current;
    private QueryResult prev;

    /**
     * When enough time has passed since the last time we actually returned
     * results, it's possible to pad the updatedAt query parameter to avoid
     * receiving the same issues over and over, only to then filter them out.
     */
    private boolean paddingPossible = false;

    /**
     * @param issueProject The IssueProject to poll from
     * @param startUpPadding The amount of historic time to include in the
     *                       very first query
     */
    public IssuePoller(IssueProject issueProject, Duration startUpPadding) {
        this.issueProject = issueProject;
        this.timeStampQueryPrecision = issueProject.issueTracker().timeStampQueryPrecision();
        this.initialUpdatedAt = ZonedDateTime.now().minus(startUpPadding);
    }

    public List<Issue> updatedIssues() {
        var beforeQuery = Instant.now();
        List<Issue> issues = queryIssues();
        var afterQuery = Instant.now();

        // Convert the query result into a map
        var issuesMap = issues.stream().collect(Collectors.toMap(Issue::id, i -> i));

        // Find the max updatedAt value in the result set. Fall back on the previous
        // value (happens if no results were returned), or the initialUpdatedAt (if
        // no results have been found at all so far).
        var maxUpdatedAt = issues.stream()
                .map(Issue::updatedAt)
                .max(Comparator.naturalOrder())
                .orElseGet(() -> prev != null ? prev.maxUpdatedAt : initialUpdatedAt);

        // Filter the results
        var filtered = issues.stream()
                .filter(this::isUpdated)
                .toList();

        var withRetries = addRetries(filtered);

        // If nothing will be returned, update the paddingPossible state if enough time
        // has passed since last we found something.
        if (withRetries.isEmpty()) {
            if (prev != null && prev.afterQuery.isBefore(beforeQuery.minus(timeStampQueryPrecision))) {
                paddingPossible = true;
            }
        } else {
            paddingPossible = false;
        }

        // Save the state of the current query results
        current = new QueryResult(issuesMap, maxUpdatedAt, afterQuery, withRetries);

        log.info("Found " + withRetries.size() + " updated issues for " + issueProject.name());
        return withRetries;
    }

    /**
     * After calling getUpdatedIssues(), this method must be called to acknowledge
     * that all the issues returned have been handled. If not, the previous results will be
     * included in the next call to getUpdatedIssues() again.
     */
    public synchronized void lastBatchHandled() {
        if (current != null) {
            prev = current;
            current = null;
            // Remove any returned PRs from the retry/quarantine sets
            prev.result.forEach(pr -> retryMap.remove(pr.id()));
        }
    }

    public synchronized void retryIssue(Issue issue) {
        retryMap.put(issue.id(), issue);
    }

    private List<Issue> queryIssues() {
        ZonedDateTime queryAfter;
        if (prev == null || prev.maxUpdatedAt == null) {
            queryAfter = initialUpdatedAt;
        } else if (paddingPossible) {
            // If we haven't found any actual results for long enough,
            // we can pad on the query precision to avoid fetching the
            // last returned issue over and over.
            queryAfter = prev.maxUpdatedAt.plus(timeStampQueryPrecision);
        } else {
            queryAfter = prev.maxUpdatedAt;
        }
        log.fine("Fetching issues updated after " + queryAfter);
        return queryIssues(issueProject, queryAfter);
    }

    /**
     * Subclasses can override this method to query for specific kinds of issues.
     * @param issueProject IssueProject to run query on
     * @param updatedAfter Timestamp for updatedAt query
     */
    protected List<Issue> queryIssues(IssueProject issueProject, ZonedDateTime updatedAfter) {
        return issueProject.issues(updatedAfter);
    }

    /**
     * Evaluates if an issue has been updated since the previous query result.
     */
    private boolean isUpdated(Issue issue) {
        if (prev == null) {
            return true;
        }
        var issuePrev = prev.issues.get(issue.id());
        if (issuePrev == null || issue.updatedAt().isAfter(issuePrev.updatedAt())) {
            return true;
        }
        return false;
    }

    /**
     * Returns a list of all prs with retries added.
     */
    private synchronized List<Issue> addRetries(List<Issue> issues) {
        if (retryMap.isEmpty()) {
            return issues;
        } else {
            // Find the retries not already present in the issues list
            var retries = retryMap.values().stream()
                    .filter(retryIssue -> issues.stream().noneMatch(issue -> issue.id().equals(retryIssue.id())))
                    .toList();
            if (retries.isEmpty()) {
                return issues;
            } else {
                return Stream.concat(issues.stream(), retries.stream()).toList();
            }
        }
    }
}
