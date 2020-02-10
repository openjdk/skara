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
package org.openjdk.skara.vcs;

import java.util.Objects;

public class Status {
    private enum Operation {
        ADDED,
        DELETED,
        RENAMED,
        COPIED,
        MODIFIED,
        UNMERGED
    }

    private Operation op;
    private int score;

    private Status(Operation op, int score) {
        this.op = op;
        this.score = score;
    }

    public boolean isAdded() {
        return op == Operation.ADDED;
    }

    public boolean isDeleted() {
        return op == Operation.DELETED;
    }

    public boolean isRenamed() {
        return op == Operation.RENAMED;
    }

    public boolean isCopied() {
        return op == Operation.COPIED;
    }

    public boolean isModified() {
        return op == Operation.MODIFIED;
    }

    public boolean isUnmerged() {
        return op == Operation.UNMERGED;
    }

    public int score() {
        return score;
    }

    public static Status from(char c) {
        if (c == 'A') {
            return new Status(Operation.ADDED, -1);
        }

        if (c == 'M') {
            return new Status(Operation.MODIFIED, -1);
        }

        if (c == 'D') {
            return new Status(Operation.DELETED, -1);
        }

        if (c == 'U') {
            return new Status(Operation.UNMERGED, -1);
        }

        if (c == 'R') {
            return new Status(Operation.RENAMED, -1);
        }

        if (c == 'C') {
            return new Status(Operation.COPIED, -1);
        }

        throw new IllegalArgumentException("Invalid status character: " + c);
    }

    public static Status from(String s) {
        if (s == null || s.isEmpty()) {
            throw new IllegalArgumentException("Empty status string");
        }

        var c = s.charAt(0);
        if (c == 'A') {
            return new Status(Operation.ADDED, -1);
        }
        if (c == 'M') {
            return new Status(Operation.MODIFIED, -1);
        }
        if (c == 'D') {
            return new Status(Operation.DELETED, -1);
        }
        if (c == 'U') {
            return new Status(Operation.UNMERGED, -1);
        }

        var score = 0;
        try {
            score = Integer.parseInt(s.substring(1));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid score", e);
        }

        if (score < 0 || score > 100) {
            throw new IllegalArgumentException("Score must be between 0 and 100: " + score);
        }

        if (c == 'R') {
            return new Status(Operation.RENAMED, score);
        }
        if (c == 'C') {
            return new Status(Operation.COPIED, score);
        }

        throw new IllegalArgumentException("Invalid status string: " + s);
    }

    @Override
    public String toString() {
        switch (op) {
            case ADDED:
                return "A";
            case DELETED:
                return "D";
            case MODIFIED:
                return "M";
            case UNMERGED:
                return "U";
            case RENAMED:
                return "R" + score;
            case COPIED:
                return "C" + score;
            default:
                throw new IllegalStateException("Invalid operation: " + op);
        }
    }

    @Override
    public int hashCode() {
        return Objects.hash(op, score);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Status)) {
            return false;
        }

        var other = (Status) o;
        return Objects.equals(op, other.op) &&
               Objects.equals(score, other.score);
    }
}
