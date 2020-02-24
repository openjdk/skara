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
package org.openjdk.skara.vcs;

public class Hash {
    private static final Hash ZERO = new Hash("0".repeat(40));
    private final String hex;

    public Hash(String hex) {
        this.hex = hex;
    }

    public static Hash zero() {
        return ZERO;
    }

    @Override
    public int hashCode() {
        return hex.hashCode();
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Hash)) {
            return false;
        }

        if (o == this) {
            return true;
        }

        return hex.equals(((Hash) o).hex());
    }

    @Override
    public String toString() {
        return hex();
    }

    public String hex() {
        return hex;
    }

    public String abbreviate() {
        return hex().substring(0, 8);
    }
}
