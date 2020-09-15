package org.openjdk.skara.bots.pr;

import org.openjdk.skara.host.HostUser;
import org.openjdk.skara.issuetracker.Comment;

import java.util.Optional;

class CommandInvocation {
    private final String id;
    private final HostUser user;
    private final CommandHandler handler;
    private final String name;
    private final String args;
    private final Comment comment;

    CommandInvocation(String id, HostUser user, CommandHandler handler, String name, String args, Comment comment) {
        this.id = id;
        this.user = user;
        this.handler = handler;
        this.name = name;
        this.args = args != null ? args.strip() : "";
        this.comment = comment;
    }

    String id() {
        return id;
    }

    HostUser user() {
        return user;
    }

    Optional<CommandHandler> handler() {
        return Optional.ofNullable(handler);
    }

    String name() {
        return name;
    }

    String args() {
        return args;
    }

    Optional<Comment> comment() {
        return Optional.ofNullable(comment);
    }

    boolean isInBody() {
        return comment == null;
    }

    boolean isInComment() {
        return comment != null;
    }
}
