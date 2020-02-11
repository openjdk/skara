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
package org.openjdk.skara.vcs.openjdk;

import java.util.regex.Pattern;

public class CommitMessageSyntax {
        private static final String OPENJDK_USERNAME_REGEX = "[-.a-z0-9]+";
        public static final String EMAIL_ADDR_REGEX = "[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]+";
        public static final String REAL_NAME_REGEX = "[-_a-zA-Z0-9][-_ a-zA-Z0-9'.]+";
        private static final String REAL_NAME_AND_EMAIL_ATTR_REGEX = REAL_NAME_REGEX + " +<" + EMAIL_ADDR_REGEX + ">";
        private static final String ATTR_REGEX = "(?:(?:" + EMAIL_ADDR_REGEX + ")|(?:" + REAL_NAME_AND_EMAIL_ATTR_REGEX + "))";

        public static final Pattern ISSUE_PATTERN = Pattern.compile("((?:[A-Z][A-Z0-9]+-)?[0-9]+): (\\S.*)$");
        public static final Pattern SUMMARY_PATTERN = Pattern.compile("Summary: (\\S.*)");
        public static final Pattern REVIEWED_BY_PATTERN = Pattern.compile("Reviewed-by: ((?:" + OPENJDK_USERNAME_REGEX + ")(?:, " + OPENJDK_USERNAME_REGEX + ")*)$");
        public static final Pattern CONTRIBUTED_BY_PATTERN = Pattern.compile("Contributed-by: (" + ATTR_REGEX + "(?:, " + ATTR_REGEX + ")*)$");
        public static final Pattern CO_AUTHOR_PATTERN = Pattern.compile("Co-authored-by: ((?:" + REAL_NAME_AND_EMAIL_ATTR_REGEX + ")(?:, " + REAL_NAME_AND_EMAIL_ATTR_REGEX + ")*)$");
}
