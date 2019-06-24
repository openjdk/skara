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

import org.openjdk.skara.vcs.Tag;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.ArrayList;
import java.util.regex.Pattern;

class TagsCheckTests {
    private List<Issue> toList(Iterator<Issue> i) {
        var list = new ArrayList<Issue>();
        while (i.hasNext()) {
            list.add(i.next());
        }
        return list;
    }

    @Test
    void onlyDefaultTagShouldPass() throws IOException {
        var repo = new TestRepository();
        repo.setDefaultTag(new Tag("default"));
        repo.setTags(List.of(new Tag("default")));

        var allowNothing = Pattern.compile("");
        var check = new TagsCheck(allowNothing);
        var issues = toList(check.check(repo));

        assertEquals(0, issues.size());
    }

    @Test
    void allowedTagShouldPass() throws IOException {
        var repo = new TestRepository();
        var allowed = "jdk-19";
        repo.setTags(List.of(new Tag("jdk-19")));

        var check = new TagsCheck(Pattern.compile(allowed));
        var issues = toList(check.check(repo));

        assertEquals(0, issues.size());
    }

    @Test
    void additionalTagsShouldFail() throws IOException {
        var repo = new TestRepository();
        var additional = new Tag("foo");
        repo.setTags(List.of(additional));

        var allowNothing = Pattern.compile("");
        var check = new TagsCheck(allowNothing);
        var issues = toList(check.check(repo));

        assertEquals(1, issues.size());
        assertTrue(issues.get(0) instanceof TagIssue);
        var issue = (TagIssue) issues.get(0);
        assertEquals(additional, issue.tag());
        assertEquals(Severity.ERROR, issue.severity());
        assertEquals(TagsCheck.class, issue.check().getClass());
    }
}
