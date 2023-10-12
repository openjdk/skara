package org.openjdk.skara.issuetracker;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.openjdk.skara.host.HostUser;

/**
 * Tracks and caches users active state in an IssueTracker. Caching should be thread safe.
 */
public class ActiveUserTracker {

    private final IssueTracker issueTracker;
    private final Map<String, Boolean> userActiveMap = new ConcurrentHashMap<>();

    public ActiveUserTracker(IssueTracker issueTracker) {
        this.issueTracker = issueTracker;
    }

    public boolean isUserActive(String userName) {
        return userActiveMap.computeIfAbsent(userName, (u) -> issueTracker.user(u).map(HostUser::active).orElse(false));
    }
}
