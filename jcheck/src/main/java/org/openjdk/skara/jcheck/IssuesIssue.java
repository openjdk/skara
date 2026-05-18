/*
 * Copyright (c) 2019, 2026, Oracle and/or its affiliates. All rights reserved.
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

import java.util.Optional;

public class IssuesIssue extends CommitIssue {
    public enum Reason {
        MISSING,
        INVALID_FORMAT
    }

    private final Reason reason;
    private final String issue;
    private final String pattern;

    IssuesIssue(CommitIssue.Metadata metadata, Reason reason, String issue, String pattern) {
        super(metadata);
        this.reason = reason;
        this.issue = issue;
        this.pattern = pattern;
    }

    public Reason reason() {
        return reason;
    }

    public Optional<String> issue() {
        return Optional.ofNullable(issue);
    }

    public Optional<String> pattern() {
        return Optional.ofNullable(pattern);
    }

    @Override
    public void accept(IssueVisitor visitor) {
        visitor.visit(this);
    }
}
