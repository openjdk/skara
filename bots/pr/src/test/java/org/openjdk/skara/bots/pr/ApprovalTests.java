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

import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

public class ApprovalTests {
    @Test
    void simple() {
        Approval approval = new Approval("", "jdk17u-fix-request", "jdk17u-fix-yes",
                "jdk17u-fix-no", "https://example.com", true, "maintainer approval");
        assertEquals("jdk17u-fix-request", approval.requestedLabel("master"));
        assertEquals("jdk17u-fix-yes", approval.approvedLabel("master"));
        assertEquals("jdk17u-fix-no", approval.rejectedLabel("master"));
        assertEquals("https://example.com", approval.documentLink());
        assertTrue(approval.approvalComment());
        assertEquals("maintainer approval", approval.approvalTerm());
        assertTrue(approval.needsApproval("master"));

        approval = new Approval("jdk17u-fix-", "request", "yes", "no",
                "https://example.com", false, "maintainer approval");
        assertEquals("jdk17u-fix-request", approval.requestedLabel("master"));
        assertEquals("jdk17u-fix-yes", approval.approvedLabel("master"));
        assertEquals("jdk17u-fix-no", approval.rejectedLabel("master"));
        assertFalse(approval.approvalComment());
        assertEquals("maintainer approval", approval.approvalTerm());
        assertTrue(approval.needsApproval("master"));

        approval = new Approval("", "-critical-request", "-critical-approved",
                "-critical-rejected", "https://example.com", false, "critical request");
        approval.addBranchPrefix(Pattern.compile("jdk20.0.1"), "CPU23_04");
        approval.addBranchPrefix(Pattern.compile("jdk20.0.2"), "CPU23_05");
        assertEquals("CPU23_04-critical-request", approval.requestedLabel("jdk20.0.1"));
        assertEquals("CPU23_04-critical-approved", approval.approvedLabel("jdk20.0.1"));
        assertEquals("CPU23_04-critical-rejected", approval.rejectedLabel("jdk20.0.1"));
        assertEquals("CPU23_05-critical-request", approval.requestedLabel("jdk20.0.2"));
        assertEquals("CPU23_05-critical-approved", approval.approvedLabel("jdk20.0.2"));
        assertEquals("CPU23_05-critical-rejected", approval.rejectedLabel("jdk20.0.2"));
        assertFalse(approval.needsApproval("master"));
        assertTrue(approval.needsApproval("jdk20.0.1"));
        assertTrue(approval.needsApproval("jdk20.0.2"));
        assertFalse(approval.needsApproval("jdk20.0.3"));
        assertFalse(approval.approvalComment());
        assertEquals("critical request", approval.approvalTerm());
    }
}
