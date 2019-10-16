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

import java.time.ZonedDateTime;
import java.util.*;

public class Check {
    private final ZonedDateTime startedAt;
    private final ZonedDateTime completedAt;
    private final CheckStatus status;
    private final Hash hash;
    private final String metadata;
    private final String title;
    private final String summary;
    private final List<CheckAnnotation> annotations;
    private final String name;

    Check(String name, Hash hash, CheckStatus status, ZonedDateTime startedAt, ZonedDateTime completedAt, String metadata, String title, String summary, List<CheckAnnotation> annotations) {
        this.name = name;
        this.hash = hash;
        this.status = status;
        this.startedAt = startedAt;
        this.completedAt = completedAt;
        this.metadata = metadata;
        this.title = title;
        this.summary = summary;
        this.annotations = annotations;
    }

    public String name() {
        return name;
    }

    public Hash hash() {
        return hash;
    }

    public CheckStatus status() {
        return status;
    }

    public ZonedDateTime startedAt() {
        return startedAt;
    }

    public Optional<ZonedDateTime> completedAt() {
        return Optional.ofNullable(completedAt);
    }

    public Optional<String> title() {
        return Optional.ofNullable(title);
    }

    public Optional<String> summary() {
        return Optional.ofNullable(summary);
    }

    public Optional<String> metadata() {
        return Optional.ofNullable(metadata);
    }

    public List<CheckAnnotation> annotations() {
        return annotations;
    }
}
