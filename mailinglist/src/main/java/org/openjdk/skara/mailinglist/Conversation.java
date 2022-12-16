/*
 * Copyright (c) 2019, 2022, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.mailinglist;

import org.openjdk.skara.email.*;

import java.util.*;
import java.util.function.Function;
import java.util.stream.*;

public class Conversation {
    private final Email first;
    private final Map<EmailAddress, LinkedHashSet<Email>> replies = new LinkedHashMap<>();
    private final Map<EmailAddress, Email> parents = new HashMap<>();

    Conversation(Email first) {
        this.first = first;
        replies.put(first.id(), new LinkedHashSet<>());
    }

    void addReply(Email parent, Email reply) {
        if (!replies.containsKey(reply.id())) {
            var replyList = replies.get(parent.id());
            replyList.add(reply);
            replies.put(reply.id(), new LinkedHashSet<>());
        }
        if (!parents.containsKey(reply.id())) {
            parents.put(reply.id(), parent);
        } else {
            var oldParent = parents.get(reply.id());
            if (!parent.equals(oldParent)) {
                throw new RuntimeException("Email with id " + reply.id() + " seen with multiple parents: " + oldParent.id() + " and " + parent.id());
            }
        }
    }

    public Email first() {
        return first;
    }

    public List<Email> replies(Email parent) {
        return new ArrayList<>(replies.get(parent.id()));
    }

    public List<Email> allMessages() {
        var unordered = Stream.concat(Stream.of(List.of(first)), replies.values().stream())
                             .flatMap(Collection::stream)
                             .collect(Collectors.toMap(Email::id, Function.identity()));
        return replies.keySet().stream()
                      .map(unordered::get)
                      .collect(Collectors.toList());
    }

    public Email parent(Email email) {
        return parents.get(email.id());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        Conversation that = (Conversation) o;
        return Objects.equals(first, that.first) &&
                Objects.equals(replies, that.replies) &&
                Objects.equals(parents, that.parents);
    }

    @Override
    public int hashCode() {
        return Objects.hash(first, replies, parents);
    }
}
