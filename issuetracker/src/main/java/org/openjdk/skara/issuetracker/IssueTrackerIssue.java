package org.openjdk.skara.issuetracker;

import java.util.List;
import java.util.Map;
import org.openjdk.skara.json.JSONValue;

/**
 * Extension of the Issue interface with additional functionality present in a bug
 * tracking system. Extracted to an interface to facilitate test implementations.
 */
public interface IssueTrackerIssue extends Issue {
    List<Link> links();

    void addLink(Link link);

    void removeLink(Link link);

    Map<String, JSONValue> properties();

    void setProperty(String name, JSONValue value);

    void removeProperty(String name);
}
