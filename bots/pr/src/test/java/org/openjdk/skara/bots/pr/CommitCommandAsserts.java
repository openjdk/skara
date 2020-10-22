package org.openjdk.skara.bots.pr;

import org.openjdk.skara.forge.CommitComment;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class CommitCommandAsserts {
    public static void assertLastCommentContains(List<CommitComment> comments, String contains) {
        assertTrue(!comments.isEmpty());
        var lastComment = comments.get(comments.size() - 1);
        assertTrue(lastComment.body().contains(contains), lastComment.body());
    }
}
