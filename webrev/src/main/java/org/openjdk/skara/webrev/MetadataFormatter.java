/*
 * Copyright (c) 2020, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.webrev;

import org.openjdk.skara.vcs.*;
import org.openjdk.skara.vcs.openjdk.Issue;

import java.util.function.Function;

class MetadataFormatter {
    private final Function<String, String> issueLinker;

    MetadataFormatter(Function<String, String> issueLinker) {
        this.issueLinker = issueLinker;
    }

    String format(CommitMetadata metadata) {
        var prefix = metadata.hash().abbreviate() + ": ";
        var subject = metadata.message().get(0);
        var issue = Issue.fromString(subject);
        if (issueLinker != null && issue.isPresent()) {
            var id = issue.get().id();
            var desc = issue.get().description();
            var url = issueLinker.apply(id);
            return prefix + "<a href=\"" + url + "\">" + id + "</a>: " + desc;
        }
        return prefix + subject;
    }
}
