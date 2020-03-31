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
package org.openjdk.skara.forge.gitlab;

import org.openjdk.skara.forge.*;
import org.openjdk.skara.vcs.Hash;

import java.util.Objects;

class GitLabBranch implements HostedBranch {
    private final String name;
    private final Hash hash;

    GitLabBranch(String name, Hash hash) {
        this.name = name;
        this.hash = hash;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Hash hash() {
        return hash;
    }

    @Override
    public String toString() {
        return name + "@" + hash.hex();
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, hash);
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof GitLabBranch)) {
            return false;
        }

        var o = (GitLabBranch) other;
        return Objects.equals(name, o.name) && Objects.equals(hash, o.hash);
    }
}
