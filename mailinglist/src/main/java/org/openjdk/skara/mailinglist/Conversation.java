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
package org.openjdk.skara.mailinglist;

import org.openjdk.skara.email.Email;

import java.util.*;

public class Conversation {
    private final Email first;
    private final Map<Email, List<Email>> replies = new LinkedHashMap<>();
    private final Map<Email, Email> parents = new HashMap<>();

    Conversation(Email first) {
        this.first = first;
        replies.put(first, new ArrayList<>());
    }

    void addReply(Email parent, Email reply) {
        var replyList = replies.get(parent);
        replyList.add(reply);
        replies.put(reply, new ArrayList<>());
        parents.put(reply, parent);
    }

    public Email first() {
        return first;
    }

    public List<Email> replies(Email parent) {
        return new ArrayList<>(replies.get(parent));
    }

    public List<Email> allMessages() {
        return new ArrayList<>(replies.keySet());
    }

    public Email parent(Email email) {
        return parents.get(email);
    }

    public List<Email> allParents(Email email) {
        var emailParents = new ArrayList<Email>();
        while (parents.containsKey(email)) {
            var parent = parents.get(email);
            emailParents.add(parent);
            email = parent;
        }
        return emailParents;
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
