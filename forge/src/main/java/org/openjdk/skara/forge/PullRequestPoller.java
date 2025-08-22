/*
 * Copyright (c) 2022, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
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
import org.openjdk.skara.issuetracker.Issue;

/**
 * A PullRequestPoller handles querying for new and updated pull requests. It
 * guarantees that no pull request updates at the forge are missed and avoids
 * returning the same update multiple times as much as possible.
 * <p>
 * On the first call, all open PRs, and if configured, non-open PRs (up to a
 * limit), are returned. After that only updated PRs should be included.
 * <p>
 * After each call for updated pull requests, the result needs to be
 * acknowledged once it has been processed by the caller using the
 * lastBatchHandled method. Failing to acknowledge makes the next call include
 * everything from the last call again. This helps to avoid missing any updates
 * due to errors. Calling the retry/quarantine methods before lastBatchHandled
 * for any particular PR will cause that PR to be lost.
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

    // The max age for closed PRs for the initial query, and the furthest
    // back subsequent queries will ever search.
    private static final Duration UPDATED_AT_QUERY_LIMIT = Duration.ofDays(7);

    private final HostedRepository repository;
    // Negative query padding is used to compensate for the forge only updating
    // timestamps on pull requests once for set minimum duration.
    private final Duration negativeQueryPadding;
    // Positive query padding is used to work around timestamp queries being
    // inclusive down to a certain time resolution.
    private final Duration positiveQueryPadding;
    private final boolean includeClosed;

    private record PullRequestRetry(PullRequest pr, Instant when) {}
    private final Map<String, PullRequestRetry> retryMap = new HashMap<>();
    private final Map<String, PullRequestRetry> quarantineMap = new HashMap<>();

    /**
     * This record represents all the query results data needed to correctly figure
     * out if future results have been updated or not.
     */
    record QueryResult(Map<String, PullRequest> pullRequests, Map<String, Object> comparisonSnapshots,
                       ZonedDateTime maxUpdatedAt, Instant afterQuery, List<PullRequest> result,
                       /*
                        * When enough time has passed since the last time we returned results, applying
                        * negative padding to the updatedAt query parameter is no longer needed. This
                        * is indicated using this boolean.
                        */
                       boolean negativePaddingNeeded) {}
    private QueryResult current;
    private QueryResult prev;

    public PullRequestPoller(HostedRepository repository, boolean includeClosed) {
        this.repository = repository;
        this.includeClosed = includeClosed;
        negativeQueryPadding = repository.forge().minTimeStampUpdateInterval();
        positiveQueryPadding = repository.forge().timeStampQueryPrecision();
    }

    /**
     * The main API method. Call this to get updated PRs. When done processing the results
     * call lastBatchHandled() to acknowledge that all the returned PRs have been handled
     * and should not be included in the next call of this method.
     */
    public List<PullRequest> updatedPullRequests() {
        var beforeQuery = Instant.now();
        List<PullRequest> prs = queryPullRequests();
        var afterQuery = Instant.now();
        log.info("Found " + prs.size() + " updated pull requests before filtering for " +
                repository.name() + " [" + prs.stream().map(Issue::id).collect(Collectors.joining(", ")) + "]");

        // Convert the query result into a map
        var pullRequestMap = prs.stream().collect(Collectors.toMap(PullRequest::id, pr -> pr));

        // Find the max updatedAt value in the result set. Fall back on the previous
        // value (happens if no results were returned), or null (if no results have
        // been found at all so far).
        var maxUpdatedAtLimit = ZonedDateTime.now().minus(UPDATED_AT_QUERY_LIMIT);
        var maxUpdatedAt = prs.stream()
                .map(PullRequest::updatedAt)
                .filter(updatedAt -> updatedAt.isAfter(maxUpdatedAtLimit))
                .max(Comparator.naturalOrder())
                .orElseGet(() -> prev != null ? prev.maxUpdatedAt : maxUpdatedAtLimit);

        // Save the current comparisonSnapshots
        var comparisonSnapshots = fetchComparisonSnapshots(prs, maxUpdatedAt);

        // Filter the results
        var filtered = prs.stream()
                .filter(this::isUpdated)
                .toList();

        log.info("Found " + filtered.size() + " updated pull requests after filtering for " +
                repository.name() + " [" + filtered.stream().map(Issue::id).collect(Collectors.joining(", ")) + "]");

        // If nothing was left after filtering, update the paddingNeeded state if enough time
        // has passed since last we found something.
        boolean negativePaddingNeeded = true;
        if (filtered.isEmpty()) {
            if (prev != null) {
                // The afterQuery value that we save should be the time when we last
                // found something after filtering.
                afterQuery = prev.afterQuery;
                if (prev.afterQuery.isBefore(beforeQuery.minus(negativeQueryPadding)
                        .minus(positiveQueryPadding))) {
                    negativePaddingNeeded = false;
                }
            }
        }

        var withRetries = addRetries(filtered);

        var result = processQuarantined(withRetries);

        // Save the state of the current query results
        current = new QueryResult(pullRequestMap, comparisonSnapshots, maxUpdatedAt, afterQuery, result, negativePaddingNeeded);

        log.info("Found " + result.size() + " updated pull requests for " + repository.name() +
                " [" + result.stream().map(Issue::id).collect(Collectors.joining(", ")) + "]");
        return result;
    }

    /**
     * After calling updatedPullRequests(), this method must be called to acknowledge
     * that all the PRs returned have been handled. If not, the previous results will be
     * included in the next call to updatedPullRequests() again.
     * <p>
     * This method must be called before any retry/quarantine method is called for a pr
     * returned by the last updatedPullRequest call, otherwise retries may be lost.
     * <p>
     * The typical pattern is to call this last in the getPeriodicItems/run method of a
     * bot or WorkItem, before any generated WorkItems are published (by being returned
     * to the bot runner).
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
     * Schedules a pull request to be included in the next update that happens after a
     * certain time, unless it is updated before that. Can be used to throttle retries.
     * @param pr PullRequest to retry
     * @param at Time at which to process it
     */
    public synchronized void retryPullRequest(PullRequest pr, Instant at) {
        retryMap.put(pr.id(), new PullRequestRetry(pr, at));
    }

    /**
     * Schedules a pull request to be included in the next update that happens after a
     * quarantine period has passed. If a quarantined pull request is returned by a
     * query, it will be removed from the result set until the quarantine time has
     * passed.
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
                log.fine("Fetching all open and recent closed pull requests for " + repository.name());
                // We need to guarantee that all open PRs are always included in the first round.
                // The pullRequests(ZonedDateTime) call has a size limit, so may leave some out.
                // There may also be open PRs that haven't been updated since the closed age limit.
                var openPrs = repository.openPullRequests();
                var allPrs = repository.pullRequestsAfter(ZonedDateTime.now().minus(UPDATED_AT_QUERY_LIMIT));
                return Stream.concat(openPrs.stream(), allPrs.stream().filter(pr -> !pr.isOpen())).toList();
            } else {
                log.fine("Fetching all open pull requests for " + repository.name());
                return repository.openPullRequests();
            }
        } else {
            var queryUpdatedAt = prev.negativePaddingNeeded
                    ? prev.maxUpdatedAt.minus(negativeQueryPadding) : prev.maxUpdatedAt.plus(positiveQueryPadding);
            if (includeClosed) {
                log.fine("Fetching open and closed pull requests updated after " + queryUpdatedAt + " for " + repository.name());
                return repository.pullRequestsAfter(queryUpdatedAt);
            } else {
                log.fine("Fetching open pull requests updated after " + queryUpdatedAt + " for " + repository.name());
                return repository.openPullRequestsAfter(queryUpdatedAt);
            }
        }
    }

    private Map<String, Object> fetchComparisonSnapshots(List<PullRequest> prs, ZonedDateTime maxUpdatedAt) {
        return prs.stream()
                .filter(pr -> !pr.updatedAt().isBefore(maxUpdatedAt.minus(negativeQueryPadding)))
                .collect(Collectors.toMap(Issue::id, PullRequest::snapshot));
    }

    /**
     * Evaluates if a PR has been updated since the previous query result.
     * First checks updatedAt and then the comparisonSnapshot of the PR if
     * present in the prev data.
     */
    private boolean isUpdated(PullRequest pr) {
        if (prev == null) {
            return true;
        }
        var prPrev = prev.pullRequests.get(pr.id());
        if (prPrev == null || pr.updatedAt().isAfter(prPrev.updatedAt())) {
            return true;
        }
        if (!pr.snapshot().equals(prev.comparisonSnapshots.get(pr.id()))) {
            return true;
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

    // Expose the query results to tests
    QueryResult getCurrentQueryResult() {
        return current;
    }
}
