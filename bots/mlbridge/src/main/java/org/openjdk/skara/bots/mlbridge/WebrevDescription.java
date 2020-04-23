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
package org.openjdk.skara.bots.mlbridge;

import java.net.URI;

public class WebrevDescription {
    public enum Type {
        FULL,
        INCREMENTAL,
        MERGE_TARGET,
        MERGE_SOURCE,
        MERGE_CONFLICT
    }

    private final URI uri;
    private final Type type;
    private final String description;

    public WebrevDescription(URI uri, Type type, String description) {
        this.uri = uri;
        this.type = type;
        this.description = description;
    }

    public WebrevDescription(URI uri, Type type) {
        this.uri = uri;
        this.type = type;
        this.description = null;
    }

    public Type type() {
        return type;
    }

    public URI uri() {
        return uri;
    }

    public String label() {
        switch (type) {
            case FULL:
                return "Full";
            case INCREMENTAL:
                return "Incremental";
            case MERGE_TARGET:
                return "Merge target" + (description != null ? " (" + description + ")" : "");
            case MERGE_SOURCE:
                return "Merge source" + (description != null ? " (" + description + ")" : "");
            case MERGE_CONFLICT:
                return "Merge conflicts" + (description != null ? " (" + description + ")" : "");

        }
        throw new RuntimeException("Unknown type");
    }

    public String shortLabel() {
        switch (type) {
            case FULL:
                return "full";
            case INCREMENTAL:
                return "incr";
            case MERGE_TARGET:
                return description != null ? description : "merge target";
            case MERGE_SOURCE:
                return description != null ? description : "merge source";
            case MERGE_CONFLICT:
                return "merge conflicts";

        }
        throw new RuntimeException("Unknown type");
    }
}
