/*
 * Copyright (c) 2018, 2019, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.issuetracker;

import org.openjdk.skara.host.HostUser;

import java.time.ZonedDateTime;
import java.util.Objects;

public class Comment {
    private final String id;
    private final String body;
    private final HostUser author;
    private final ZonedDateTime createdAt;
    private final ZonedDateTime updatedAt;

    public Comment(String id, String body, HostUser author, ZonedDateTime createdAt, ZonedDateTime updatedAt) {
        this.id = id;
        this.body = body;
        this.author = author;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String id() {
        return id;
    }

    public String body() {
        return body;
    }

    public HostUser author() {
        return author;
    }

    public ZonedDateTime createdAt() {
        return createdAt;
    }

    public ZonedDateTime updatedAt() {
        return updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        Comment comment = (Comment) o;
        return id.equals(comment.id) &&
                body.equals(comment.body) &&
                author.equals(comment.author) &&
                createdAt.equals(comment.createdAt) &&
                updatedAt.equals(comment.updatedAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, body, author, createdAt, updatedAt);
    }

    @Override
    public String toString() {
        return body;
    }
}
