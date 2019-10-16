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

import java.util.Optional;

public class CheckAnnotation {
    public final String path;
    private final int startLine;
    private final int endLine;
    private final Integer startColumn;
    private final Integer endColumn;
    private final String title;
    private final String message;
    private final CheckAnnotationLevel level;

    CheckAnnotation(String path, int startLine, int endLine, CheckAnnotationLevel level, Integer startColumn, Integer endColumn, String title, String message) {
        this.path = path;
        this.startLine = startLine;
        this.endLine = endLine;
        this.startColumn = startColumn;
        this.endColumn = endColumn;
        this.title = title;
        this.message = message;
        this.level = level;
    }

    public String path() {
        return path;
    }

    public int startLine() {
        return startLine;
    }

    public int endLine() {
        return endLine;
    }

    public Optional<Integer> startColumn() {
        return Optional.ofNullable(startColumn);
    }

    public Optional<Integer> endColumn() {
        return Optional.ofNullable(endColumn);
    }

    public Optional<String> title() {
        return Optional.ofNullable(title);
    }

    public String message() {
        return message;
    }

    public CheckAnnotationLevel level() {
        return level;
    }
}
