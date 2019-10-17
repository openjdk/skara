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
package org.openjdk.skara.forge;

import org.openjdk.skara.vcs.Hash;

import java.time.*;
import java.util.*;

public class CheckBuilder {

    private final String name;
    private final Hash hash;

    private String metadata;
    private List<CheckAnnotation> annotations;
    private CheckStatus status;
    private ZonedDateTime startedAt;
    private ZonedDateTime completedAt;
    private String title;
    private String summary;

    private CheckBuilder(String name, Hash hash) {
        this.name = name;
        this.hash = hash;

        annotations = new ArrayList<>();
        status = CheckStatus.IN_PROGRESS;
        startedAt = ZonedDateTime.now(ZoneOffset.UTC);
    }

    public static CheckBuilder create(String name, Hash hash) {
        return new CheckBuilder(name, hash);
    }

    public static CheckBuilder from(Check c) {
        var builder = new CheckBuilder(c.name(), c.hash());
        builder.startedAt = c.startedAt();
        builder.status = c.status();
        builder.annotations = c.annotations();

        if (c.title().isPresent()) {
            builder.title = c.title().get();
        }
        if (c.summary().isPresent()) {
            builder.summary = c.summary().get();
        }
        if (c.completedAt().isPresent()) {
            builder.completedAt = c.completedAt().get();
        }
        if (c.metadata().isPresent()) {
            builder.metadata = c.metadata().get();
        }

        return builder;
    }

    public CheckBuilder metadata(String metadata) {
        this.metadata = metadata;
        return this;
    }

    public CheckBuilder annotation(CheckAnnotation annotation) {
        annotations.add(annotation);
        return this;
    }

    public CheckBuilder complete(boolean success) {
        status = success ? CheckStatus.SUCCESS : CheckStatus.FAILURE;
        completedAt = ZonedDateTime.now();
        return this;
    }

    public CheckBuilder complete(boolean success, ZonedDateTime completedAt) {
        status = success ? CheckStatus.SUCCESS : CheckStatus.FAILURE;
        this.completedAt = completedAt;
        return this;
    }

    public CheckBuilder cancel() {
        status = CheckStatus.CANCELLED;
        completedAt = ZonedDateTime.now();
        return this;
    }

    public CheckBuilder cancel(ZonedDateTime completedAt) {
        status = CheckStatus.CANCELLED;
        this.completedAt = completedAt;
        return this;
    }

    public CheckBuilder startedAt(ZonedDateTime startedAt) {
        this.startedAt = startedAt;
        return this;
    }

    public CheckBuilder title(String title) {
        this.title = title;
        return this;
    }

    public CheckBuilder summary(String summary) {
        this.summary = summary;
        return this;
    }

    public Check build() {
        return new Check(name, hash, status, startedAt, completedAt, metadata, title, summary, annotations);
    }
}
