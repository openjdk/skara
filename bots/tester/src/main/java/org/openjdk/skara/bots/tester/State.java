/*
 * Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.bots.tester;

import org.openjdk.skara.forge.PullRequest;
import org.openjdk.skara.issuetracker.Comment;

class State {
    private final Stage stage;
    private final Comment requested;
    private final Comment pending;
    private final Comment approval;
    private final Comment started;
    private final Comment cancelled;
    private final Comment finished;

    private State(Stage stage, Comment requested,
                               Comment pending,
                               Comment approval,
                               Comment started,
                               Comment cancelled,
                               Comment finished) {
        this.stage = stage;
        this.requested = requested;
        this.pending = pending;
        this.approval = approval;
        this.started = started;
        this.cancelled = cancelled;
        this.finished = finished;
    }

    Stage stage() {
        return stage;
    }

    Comment requested() {
        return requested;
    }

    Comment pending() {
        return pending;
    }

    Comment approval() {
        return approval;
    }

    Comment started() {
        return started;
    }

    Comment cancelled() {
        return cancelled;
    }

    Comment finished() {
        return finished;
    }

    static State from(PullRequest pr, String approverGroupId) {
        Comment requested = null;
        Comment pending = null;
        Comment approval = null;
        Comment started = null;
        Comment cancelled = null;
        Comment error = null;
        Comment finished = null;

        var isApproved = false;

        var host = pr.repository().forge();
        var comments = pr.comments();
        var start = -1;
        for (var i = comments.size() - 1; i >=0; i--) {
            var comment = comments.get(i);
            var lines = comment.body().split("\n");
            if (lines.length == 1 &&
                lines[0].startsWith("/test") &&
                !lines[0].startsWith("/test approve") &&
                !lines[0].startsWith("/test cancel")) {
                requested = comment;
                start = i;
                break;
            }
        }

        if (requested != null) {
            var applicable = comments.subList(start, comments.size());
            for (var comment : applicable) {
                var body = comment.body();
                var author = comment.author();
                if (author.equals(host.currentUser())) {
                    var lines = body.split("\n");
                    switch (lines[0]) {
                        case "<!-- TEST PENDING -->":
                            pending = comment;
                            break;
                        case "<!-- TEST STARTED -->":
                            started = comment;
                            break;
                        case "<!-- TEST ERROR -->":
                            error = comment;
                            break;
                        case "<!-- TEST FINISHED -->":
                            finished = comment;
                            break;
                    }
                } else if (body.equals("/test approve")) {
                    approval = comment;
                    if (host.isMemberOf(approverGroupId, author)) {
                        isApproved = true;
                    }
                } else if (body.equals("/test cancel")) {
                    if (comment.author().equals(requested.author())) {
                        cancelled = comment;
                    }
                } else if (body.startsWith("/test")) {
                    if (host.isMemberOf(approverGroupId, author)) {
                        isApproved = true;
                    }
                }
            }
        }

        Stage stage = null;
        if (error != null) {
            stage = Stage.ERROR;
        } else if (cancelled != null) {
            stage = Stage.CANCELLED;
        } else if (finished != null) {
            stage = Stage.FINISHED;
        } else if (started != null) {
            stage = Stage.STARTED;
        } else if (requested != null && isApproved) {
            stage = Stage.APPROVED;
        } else if (requested != null && pending != null) {
            stage = Stage.PENDING;
        } else if (requested != null) {
            stage = Stage.REQUESTED;
        } else {
            stage = Stage.NA;
        }

        return new State(stage, requested, pending, approval, started, cancelled, finished);
    }
}
