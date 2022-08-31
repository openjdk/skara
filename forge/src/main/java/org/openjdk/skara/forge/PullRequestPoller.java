package org.openjdk.skara.forge;

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
import org.openjdk.skara.issuetracker.Comment;
import org.openjdk.skara.issuetracker.Issue;
import org.openjdk.skara.vcs.Hash;

/**
 * A PullRequestPoller handles querying for new and updated pull requests. It
 * guarantees that no pull request updates at the forge are missed and avoids
 * returning the same update multiple times as much as possible.
 * <p>
 * On the first call, all open PRs, and if configured, non-open PRs (up to a
 * limit), are returned. After that only updated PRs should be included.
 * <p>
 * After each call for updated pull requests, the result needs to be
 * acknowledged once it has been processed by the caller. Failing to
 * acknowledge makes the next call include everything from the last call
 * again. This helps to avoid missing any updates due to errors.
 * <p>
 * In addition to this, it's also possible to schedule PRs for retries with
 * or without quarantine. A regular retry will not block the same PR if it
 * gets returned by the regular query, but doing so will cancel the future
 * retry. A quarantine type retry will completely block that PR until the
 * quarantine is lifted. In both cases, the actual object returned will be
 * the same one provided in the retry call, unless a newer instance has
 * been returned by a query since then.
 */
public class PullRequestPoller {

    private static final Logger log = Logger.getLogger(PullRequestPoller.class.getName());

    private static final Duration CLOSED_PR_AGE_LIMIT = Duration.ofDays(7);

    private final HostedRepository repository;
    private final Duration queryPadding;
    private final boolean includeClosed;
    private final boolean trustUpdatedAt;
    private final boolean checkComments;
    private final boolean checkReviews;

    private record PullRequestRetry(PullRequest pr, Instant when) {}
    private final Map<String, PullRequestRetry> retryMap = new HashMap<>();
    private final Map<String, PullRequestRetry> quarantineMap = new HashMap<>();

    /**
     * This record represents all the query results data needed to correctly figure
     * out if future results have been updated or not.
     */
    private record QueryResult(Map<String, PullRequest> pullRequests, Map<String, List<Comment>> comments,
                               Map<String, List<Review>> reviews, ZonedDateTime maxUpdatedAt, Instant afterQuery,
                               List<PullRequest> result) {}
    private QueryResult current;
    private QueryResult prev;

    /**
     * When enough time has past since the last time we actually received results
     * padding the updatedAt query parameter is no longer needed. This is indicated
     * using this boolean.
     */
    private boolean paddingNeeded = true;

    public PullRequestPoller(HostedRepository repository, boolean includeClosed, boolean commentsRelevant,
            boolean reviewsRelevant) {
        this.repository = repository;
        this.includeClosed = includeClosed;
        queryPadding = repository.forge().minTimeStampUpdateInterval();
        if (!queryPadding.isZero()) {
            trustUpdatedAt = false;
            checkComments = commentsRelevant;
            checkReviews = reviewsRelevant;
        } else {
            trustUpdatedAt = true;
            checkComments = false;
            checkReviews = false;
        }
    }

    /**
     * The main API method. Call this go get updated PRs. When done processing the results
     * call lastBatchHandled() to acknowledge that all the returned PRs have been handled
     * and should not be included in the next call of this method.
     */
    public List<PullRequest> updatedPullRequests() {
        var beforeQuery = Instant.now();
        List<PullRequest> prs = queryPullRequests();

        // If nothing was found. Update the paddingNeeded state if enough time
        // has passed since last we found something.
        if (prs.isEmpty()) {
            if (prev != null && prev.afterQuery.isBefore(beforeQuery.minus(queryPadding))) {
                paddingNeeded = false;
            }
        } else {
            paddingNeeded = true;
        }
        var afterQuery = Instant.now();

        // Convert the query result into a map
        var pullRequestMap = prs.stream().collect(Collectors.toMap(PullRequest::id, pr -> pr));

        // Find the max updatedAt value in the result set. Fall back on the previous
        // value (happens if no results were returned), or null (if no results have
        // been found at all so far).
        var maxUpdatedAt = prs.stream()
                .map(PullRequest::updatedAt)
                .max(Comparator.naturalOrder())
                .orElseGet(() -> prev != null ? prev.maxUpdatedAt : null);

        // If checking comments, save the current state of comments for future
        // comparisons.
        var commentsMap = fetchComments(prs, maxUpdatedAt);

        // If checking reviews, save the current state of reviews for future
        // comparisons.
        var reviewsMap = fetchReviews(prs, maxUpdatedAt);

        // Filter the results
        var filtered = prs.stream()
                .filter(this::isUpdated)
                .toList();

        var withRetries = addRetries(filtered);

        var result = processQuarantined(withRetries);

        // Save the state of the current query results
        current = new QueryResult(pullRequestMap, commentsMap, reviewsMap, maxUpdatedAt, afterQuery, result);

        return result;
    }

    /**
     * After calling getUpdatedPullRequests(), this method must be called to acknowledge
     * that all the PRs returned have been handled. If not, the previous results will be
     * included in the next call to getUpdatedPullRequests() again.
     */
    public synchronized void lastBatchHandled() {
        if (current != null) {
            prev = current;
            current = null;
            // Remove any returned PRs from the retry/quarantine sets
            prev.result.forEach(pr -> retryMap.remove(pr.id()));
            prev.result.forEach(pr -> quarantineMap.remove(pr.id()));
        }
    }

    /**
     * If handling of a pull request fails, call this to have it be included in the next
     * update, regardless of if it was updated or not.
     * @param pr PullRequest to retry
     */
    public synchronized void retryPullRequest(PullRequest pr) {
        retryPullRequest(pr, Instant.MIN);
    }

    /**
     * Schedules a pull request to be re-evaluated after a certain time, unless it is
     * updated before that.
     * @param pr PullRequest to retry
     * @param at Time at which to process it
     */
    public synchronized void retryPullRequest(PullRequest pr, Instant at) {
        retryMap.put(pr.id(), new PullRequestRetry(pr, at));
    }

    /**
     * Schedules a pull request to be retried after a quarantine period has passed.
     * If a quarantined pull request is returned by a query, it will be removed from
     * the result set until the quarantine time has passed.
     * @param pr PullRequest to quarantine
     * @param until Time at which the quarantine is lifted
     */
    public synchronized void quarantinePullRequest(PullRequest pr, Instant until) {
        quarantineMap.put(pr.id(), new PullRequestRetry(pr, until));
    }

    /**
     * Queries the repository for pull requests. On the first round (or until any
     * results have been received), get all pull requests. After that limit the
     * results using the maxUpdatedAt value from the previous results. Use padding
     * if needed to guarantee that we never miss an update.
     */
    private List<PullRequest> queryPullRequests() {
        if (prev == null || prev.maxUpdatedAt == null) {
            if (includeClosed) {
                log.info("Fetching all open and recent closed pull requests for " + repository.name());
                // We need to guarantee that all open PRs are always included in the first round.
                // The pullRequests(ZonedDateTime) call has a size limit, so may leave some out.
                // There may also be open PRs that haven't been updated since the closed age limit.
                var openPrs = repository.pullRequests();
                var allPrs = repository.pullRequests(ZonedDateTime.now().minus(CLOSED_PR_AGE_LIMIT));
                return Stream.concat(openPrs.stream(), allPrs.stream().filter(pr -> !pr.isOpen())).toList();
            } else {
                log.info("Fetching all open pull requests for " + repository.name());
                return repository.pullRequests();
            }
        } else {
            var queryUpdatedAt = paddingNeeded ? prev.maxUpdatedAt.minus(queryPadding) : prev.maxUpdatedAt;
            if (includeClosed) {
                log.info("Fetching open and closed pull requests updated after " + queryUpdatedAt + " for " + repository.name());
                return repository.pullRequests(queryUpdatedAt);
            } else {
                log.info("Fetching open pull requests updated after " + queryUpdatedAt + " for " + repository.name());
                return repository.openPullRequestsAfter(queryUpdatedAt);
            }
        }
    }

    private Map<String, List<Comment>> fetchComments(List<PullRequest> prs, ZonedDateTime maxUpdatedAt) {
        if (checkComments) {
            return prs.stream()
                    .filter(pr -> pr.updatedAt().isAfter(maxUpdatedAt.minus(queryPadding)))
                    .collect(Collectors.toMap(Issue::id, Issue::comments));
        } else {
            return Map.of();
        }
    }

    private Map<String, List<Review>> fetchReviews(List<PullRequest> prs, ZonedDateTime maxUpdatedAt) {
        Map<String, List<Review>> reviewsMap;
        if (checkReviews) {
            reviewsMap = prs.stream()
                    .filter(pr -> pr.updatedAt().isAfter(maxUpdatedAt.minus(queryPadding)))
                    .collect(Collectors.toMap(Issue::id, PullRequest::reviews));
        } else {
            reviewsMap = Map.of();
        }
        return reviewsMap;
    }

    /**
     * Evaluates if a PR has been updated since the previous query result. If we
     * can trust updatedAt from the forge, it's a simple comparison, otherwise
     * we need to compare the complete contents of the PR object, as well as
     * comments and/or reviews as configured.
     */
    private boolean isUpdated(PullRequest pr) {
        if (prev == null) {
            return true;
        }
        var prPrev = prev.pullRequests.get(pr.id());
        if (prPrev == null || pr.updatedAt().isAfter(prPrev.updatedAt())) {
            return true;
        }
        if (!trustUpdatedAt) {
            if (!pr.equals(prPrev)) {
                return true;
            }
            if (checkComments && !pr.comments().equals(prev.comments.get(pr.id()))) {
                return true;
            }
            if (checkReviews && !pr.reviews().equals(prev.reviews.get(pr.id()))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns a list of all prs with retries added.
     */
    private synchronized List<PullRequest> addRetries(List<PullRequest> prs) {
        if (retryMap.isEmpty()) {
            return prs;
        } else {
            // Find the retries that have passed their at time.
            var now = Instant.now();
            var retries = retryMap.values().stream()
                    .filter(prRetry -> prRetry.when.isBefore(now))
                    .filter(prRetry -> prs.stream().noneMatch(pr -> pr.id().equals(prRetry.pr.id())))
                    .map(PullRequestRetry::pr)
                    .toList();
            if (retries.isEmpty()) {
                return prs;
            } else {
                return Stream.concat(prs.stream(), retries.stream()).toList();
            }
        }
    }

    /**
     * Returns a list of all prs with still quarantined prs removed and newly lifted
     * prs added.
     */
    private synchronized List<PullRequest> processQuarantined(List<PullRequest> prs) {
        if (quarantineMap.isEmpty()) {
            return prs;
        } else {
            var now = Instant.now();
            // Replace the PR instances in the quarantineMap with any freshly fetched PRs.
            // By doing this, we will always return the most up-to-date version of the PR
            // that we have seen so far.
            prs.forEach(pr -> {
                if (quarantineMap.containsKey(pr.id())) {
                    quarantineMap.put(pr.id(), new PullRequestRetry(pr, quarantineMap.get(pr.id()).when));
                }
            });
            // Find all quarantined PRs that are now past the time
            var pastQuarantine = quarantineMap.values().stream()
                    .filter(prRetry -> prRetry.when.isBefore(now))
                    .filter(prRetry -> prs.stream().noneMatch(pr -> pr.id().equals(prRetry.pr.id())))
                    .map(PullRequestRetry::pr)
                    .toList();
            // Find all still quarantined PRs
            var stillQuarantined = quarantineMap.values().stream()
                    .filter(prRetry -> !prRetry.when.isBefore(now))
                    .map(prRetry -> prRetry.pr.id())
                    .collect(Collectors.toSet());
            return Stream.concat(
                            prs.stream().filter(pr -> !stillQuarantined.contains(pr.id())),
                            pastQuarantine.stream())
                    .toList();
        }
    }
}
