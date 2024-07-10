/*
 * Copyright (c) 2019, 2024, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.vcs;

import java.nio.file.Path;
import java.util.Objects;

public class Submodule {
    private final Hash hash;
    private final Path path;
    private final String pullPath;

    public Submodule(Hash hash, Path path, String pullPath) {
        this.hash = hash;
        this.path = path;
        this.pullPath = pullPath;
    }

    public Hash hash() {
        return hash;
    }

    public Path path() {
        return path;
    }

    public String pullPath() {
        return pullPath;
    }

    @Override
    public String toString() {
        return pullPath + " " + hash + " " + path;
    }

    @Override
    public int hashCode() {
        return Objects.hash(hash, path, pullPath);
    }

    @Override
    public boolean equals(Object other) {
        if (other == this) {
            return true;
        }

        if (!(other instanceof Submodule o)) {
            return false;
        }

        return Objects.equals(hash, o.hash) &&
               Objects.equals(path, o.path) &&
               Objects.equals(pullPath, o.pullPath);
    }
}
