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
package org.openjdk.skara.jcheck;

public class HgTagCommitIssue extends CommitIssue {
    public static enum Error {
        TOO_MANY_LINES,
        BAD_FORMAT,
        TOO_MANY_CHANGES,
        TAG_DIFFERS,
    }

    private final Error error;

    HgTagCommitIssue(Error error, CommitIssue.Metadata metadata) {
        super(metadata);
        this.error = error;
    }

    public Error error() {
        return error;
    }

    @Override
    public void accept(IssueVisitor v) {
        v.visit(this);
    }

    static HgTagCommitIssue tooManyLines(CommitIssue.Metadata metadata) {
        return new HgTagCommitIssue(Error.TOO_MANY_LINES, metadata);
    }

    static HgTagCommitIssue badFormat(CommitIssue.Metadata metadata) {
        return new HgTagCommitIssue(Error.BAD_FORMAT, metadata);
    }

    static HgTagCommitIssue tooManyChanges(CommitIssue.Metadata metadata) {
        return new HgTagCommitIssue(Error.TOO_MANY_CHANGES, metadata);
    }

    static HgTagCommitIssue tagDiffers(CommitIssue.Metadata metadata) {
        return new HgTagCommitIssue(Error.TAG_DIFFERS, metadata);
    }
}
