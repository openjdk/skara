/*
 * Copyright (c) 2023, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.bots.pr;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

public class Approval {
    private final String prefix;
    private final String request;
    private final String approved;
    private final String rejected;
    private final String documentLink;
    private final Map<Pattern, String> branchPrefixes;
    private final boolean approvalComment;
    private final String approvalTerm;

    public Approval(String prefix, String request, String approved, String rejected, String documentLink, boolean approvalComment, String approvalTerm) {
        this.prefix = prefix;
        this.request = request;
        this.approved = approved;
        this.rejected = rejected;
        this.branchPrefixes = new HashMap<>();
        this.documentLink = documentLink;
        this.approvalComment = approvalComment;
        this.approvalTerm = approvalTerm;
    }

    public void addBranchPrefix(Pattern branchPattern, String prefix) {
        branchPrefixes.put(branchPattern, prefix);
    }

    public String requestedLabel(String targetRef) {
        return prefixForRef(targetRef) + request;
    }

    public String approvedLabel(String targetRef) {
        return prefixForRef(targetRef) + approved;
    }

    public String rejectedLabel(String targetRef) {
        return prefixForRef(targetRef) + rejected;
    }

    public String documentLink() {
        return documentLink;
    }

    private String prefixForRef(String targetRef) {
        String prefix = this.prefix;
        for (var entry : branchPrefixes.entrySet()) {
            if (entry.getKey().matcher(targetRef).matches()) {
                prefix = entry.getValue();
                break;
            }
        }
        return prefix;
    }

    public boolean needsApproval(String targetRef) {
        if (branchPrefixes.isEmpty()) {
            return true;
        }
        for (var branchPattern : branchPrefixes.keySet()) {
            if (branchPattern.matcher(targetRef).matches()) {
                return true;
            }
        }
        return false;
    }

    public boolean approvalComment() {
        return approvalComment;
    }

    public String approvalTerm() {
        return approvalTerm;
    }
}
