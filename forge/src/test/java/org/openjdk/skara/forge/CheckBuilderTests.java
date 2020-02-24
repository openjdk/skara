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
package org.openjdk.skara.forge;

import org.openjdk.skara.vcs.Hash;

import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CheckBuilderTests {
    @Test
    void testFrom() {
        var name = "test";
        var title = "title";
        var summary = "summary";
        var metadata = "metadata";
        var annotation = CheckAnnotationBuilder.create("README", 0, 1, CheckAnnotationLevel.NOTICE, "Message")
                                               .build();
        var startedAt = ZonedDateTime.now();
        var completedAt = ZonedDateTime.now();
        var success = true;

        var existing = CheckBuilder.create(name, Hash.zero())
                                   .title(title)
                                   .summary(summary)
                                   .metadata(metadata)
                                   .annotation(annotation)
                                   .startedAt(startedAt)
                                   .complete(success, completedAt)
                                   .build();
        var dup = CheckBuilder.from(existing)
                              .build();

        assertEquals(existing.name(), dup.name());
        assertEquals(existing.hash(), dup.hash());
        assertEquals(existing.status(), dup.status());
        assertEquals(existing.startedAt(), dup.startedAt());
        assertEquals(existing.completedAt(), dup.completedAt());
        assertEquals(existing.title(), dup.title());
        assertEquals(existing.summary(), dup.summary());
        assertEquals(existing.metadata(), dup.metadata());
        assertEquals(existing.annotations(), dup.annotations());

        var newTitle = "new title";
        var newSummary = "new summary";
        var newMetadata = "new metadata";
        var newAnnotation = CheckAnnotationBuilder.create("FILE", 0, 1, CheckAnnotationLevel.NOTICE, "Message")
                                                  .build();
        var newStartedAt = ZonedDateTime.now();
        var newCompletedAt = ZonedDateTime.now();
        var newSuccess = false;

        var modified = CheckBuilder.from(existing)
                                   .title(newTitle)
                                   .summary(newSummary)
                                   .metadata(newMetadata)
                                   .annotation(newAnnotation)
                                   .startedAt(newStartedAt)
                                   .complete(newSuccess, newCompletedAt)
                                   .build();

        // existing check should not have changed
        assertEquals(dup.name(), existing.name());
        assertEquals(dup.hash(), existing.hash());
        assertEquals(dup.status(), existing.status());
        assertEquals(dup.startedAt(), existing.startedAt());
        assertEquals(dup.completedAt(), existing.completedAt());
        assertEquals(dup.title(), existing.title());
        assertEquals(dup.summary(), existing.summary());
        assertEquals(dup.metadata(), existing.metadata());
        assertEquals(dup.annotations(), existing.annotations());

        // modified should have new values except name and hash and inherit annotations
        assertEquals(existing.name(), modified.name());
        assertEquals(existing.hash(), modified.hash());
        assertEquals(newStartedAt, modified.startedAt());
        assertEquals(newCompletedAt, modified.completedAt().get());
        assertEquals(newTitle, modified.title().get());
        assertEquals(newSummary, modified.summary().get());
        assertEquals(newMetadata, modified.metadata().get());
        assertEquals(List.of(annotation, newAnnotation), modified.annotations());
    }
}
