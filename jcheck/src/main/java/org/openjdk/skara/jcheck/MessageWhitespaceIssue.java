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
package org.openjdk.skara.jcheck;

public class MessageWhitespaceIssue extends CommitIssue {
    public static enum Whitespace {
        TAB,
        CR,
        TRAILING;

        public boolean isTab() {
            return this == TAB;
        }

        public boolean isCR() {
            return this == CR;
        }

        public boolean isTrailing() {
            return this == TRAILING;
        }
    }

    private final Whitespace kind;
    private final int line;

    private MessageWhitespaceIssue(CommitIssue.Metadata metadata, Whitespace kind, int line) {
        super(metadata);
        this.kind = kind;
        this.line = line;
    }

    public Whitespace kind() {
        return kind;
    }

    public int line() {
        return line;
    }

    static MessageWhitespaceIssue tab(int line, CommitIssue.Metadata metadata) {
        return new MessageWhitespaceIssue(metadata, Whitespace.TAB, line);
    }

    static MessageWhitespaceIssue cr(int line, CommitIssue.Metadata metadata) {
        return new MessageWhitespaceIssue(metadata, Whitespace.CR, line);
    }

    static MessageWhitespaceIssue trailing(int line, CommitIssue.Metadata metadata) {
        return new MessageWhitespaceIssue(metadata, Whitespace.TRAILING, line);
    }

    @Override
    public void accept(IssueVisitor visitor) {
        visitor.visit(this);
    }
}
