package org.openjdk.skara.bots.pr;

import org.openjdk.skara.host.HostUser;

import java.util.Optional;

class CommandInvocation {
    private final String id;
    private final HostUser user;
    private final CommandHandler handler;
    private final String name;
    private final String args;

    CommandInvocation(String id, HostUser user, CommandHandler handler, String name, String args) {
        this.id = id;
        this.user = user;
        this.handler = handler;
        this.name = name;
        this.args = args != null ? args.strip() : "";
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
}
