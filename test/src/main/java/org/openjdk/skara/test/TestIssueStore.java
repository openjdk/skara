package org.openjdk.skara.test;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.openjdk.skara.host.HostUser;
import org.openjdk.skara.issuetracker.Comment;
import org.openjdk.skara.issuetracker.Issue;
import org.openjdk.skara.issuetracker.IssueProject;

/**
 * Base class for backing store for issues. Represents the server side store of an Issue.
 */
public class TestIssueStore {
    private final String id;
    private final IssueProject issueProject;
    private final HostUser author;

    private Issue.State state = Issue.State.OPEN;
    private String body = "";
    private String title = "";
    private final List<Comment> comments = new ArrayList<>();
    private final Map<String, ZonedDateTime> labels = new HashMap<>();
    private final List<HostUser> assignees = new ArrayList<>();
    private final ZonedDateTime created = ZonedDateTime.now();
    private ZonedDateTime lastUpdate = created;
    private HostUser closedBy = null;

    public TestIssueStore(String id, IssueProject issueProject, HostUser author, String title, List<String> body) {
        this.id = id;
        this.issueProject = issueProject;
        this.author = author;
        this.title = title;
        this.body = String.join("\n", body);

    }

    public String id() {
        return id;
    }

    public IssueProject issueProject() {
        return issueProject;
    }

    public HostUser author() {
        return author;
    }

    public Issue.State state() {
        return state;
    }

    public String body() {
        return body;
    }

    public String title() {
        return title;
    }

    public List<Comment> comments() {
        return comments;
    }

    public Map<String, ZonedDateTime> labels() {
        return labels;
    }

    public List<String> labelNames() {
        return labels().keySet().stream().toList();
    }

    public List<HostUser> assignees() {
        return assignees;
    }

    public ZonedDateTime created() {
        return created;
    }

    public ZonedDateTime lastUpdate() {
        return lastUpdate;
    }

    public HostUser closedBy() {
        return closedBy;
    }

    public void setState(Issue.State state) {
        this.state = state;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public void setLastUpdate(ZonedDateTime lastUpdate) {
        this.lastUpdate = lastUpdate;
    }

    public void setClosedBy(HostUser closedBy) {
        this.closedBy = closedBy;
    }
}
