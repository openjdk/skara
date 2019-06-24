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
package org.openjdk.skara.host;

public class CheckAnnotationBuilder {

    private final String path;
    private final int startLine;
    private final int endLine;
    private final CheckAnnotationLevel level;
    private final String message;

    private Integer startColumn;
    private Integer endColumn;
    private String title;

    private CheckAnnotationBuilder(String path, int startLine, int endLine, CheckAnnotationLevel level, String message) {
        this.path = path;
        this.startLine = startLine;
        this.endLine = endLine;
        this.level = level;
        this.message = message;
    }

    public static CheckAnnotationBuilder create(String path, int startLine, int endLine, CheckAnnotationLevel level, String message) {
        return new CheckAnnotationBuilder(path, startLine, endLine, level, message);
    }

    public CheckAnnotationBuilder startColumn(int startColumn) {
        this.startColumn = startColumn;
        return this;
    }

    public CheckAnnotationBuilder endColumn(int endColumn) {
        this.endColumn = endColumn;
        return this;
    }

    public CheckAnnotationBuilder title(String title) {
        this.title = title;
        return this;
    }

    public CheckAnnotation build() {
        return new CheckAnnotation(path, startLine, endLine, level, startColumn, endColumn, title, message);
    }
}
