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

public class TooFewReviewersIssue extends CommitIssue {
    private final int numActual;
    private final int numRequired;
    private final String role;

    TooFewReviewersIssue(int numActual, int numRequired, String role, CommitIssue.Metadata metadata) {
        super(metadata);
        this.numActual = numActual;
        this.numRequired = numRequired;
        this.role = role;
    }

    public int numRequired() {
        return numRequired;
    }

    public int numActual() {
        return numActual;
    }

    public String role() {
        return role;
    }

    @Override
    public void accept(IssueVisitor v) {
        v.visit(this);
    }
}
