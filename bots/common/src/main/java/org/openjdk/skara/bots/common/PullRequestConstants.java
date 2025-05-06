/*
 * Copyright (c) 2023, 2025, Oracle and/or its affiliates. All rights reserved.
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

package org.openjdk.skara.bots.common;

import java.util.regex.Pattern;

public class PullRequestConstants {
    // MARKERS
    public static final String PROGRESS_MARKER = "<!-- Anything below this marker will be automatically updated, please do not edit manually! -->";
    public static final String CSR_NEEDED_MARKER = "<!-- csr: 'needed' -->";
    public static final String CSR_UNNEEDED_MARKER = "<!-- csr: 'unneeded' -->";
    public static final String JEP_MARKER = "<!-- jep: '%s' '%s' '%s' -->"; // <!-- jep: 'JEP-ID' 'ISSUE-ID' 'ISSUE-TITLE' -->
    public static final String WEBREV_COMMENT_MARKER = "<!-- mlbridge webrev comment -->";
    public static final String TEMPORARY_ISSUE_FAILURE_MARKER = "<!-- temporary issue failure -->";
    public static final String READY_FOR_SPONSOR_MARKER = "<!-- integration requested: '%s' -->";
    public static final String TOUCH_COMMAND_RESPONSE_MARKER = "<!-- touch command response -->";

    // LABELS
    public static final String CSR_LABEL = "csr";
    public static final String JEP_LABEL = "jep";
    public static final String APPROVAL_LABEL = "approval";

    // PATTERNS
    public static final Pattern JEP_MARKER_PATTERN = Pattern.compile("<!-- jep: '(.*?)' '(.*?)' '(.*?)' -->");
    public static final Pattern READY_FOR_SPONSOR_MARKER_PATTERN = Pattern.compile("<!-- integration requested: '(.*?)' -->");
}
