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

import java.util.Objects;
import java.util.Optional;

public class Author {
    private final String name;
    private final String email;

    public Author(String name) {
        this(name, null);
    }

    public Author(String name, String email) {
        this.name = name;
        this.email = email;
    }

    public String name() {
        return name;
    }

    public String email() {
        return email;
    }

    public static Author fromString(String s) {
        var open = s.indexOf('<');
        var close = s.lastIndexOf('>');
        if (open < 1 || close != (s.length() - 1)) {
            return new Author(s);
        }

        var email = s.substring(open + 1, close);
        var name = s.substring(0, open).trim();
        return new Author(name, email);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, email);
    }

    @Override
    public String toString() {
        if (email == null) {
            return name;
        }
        return name + " <" + email + ">";
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Author other)) {
            return false;
        }

        return Objects.equals(name, other.name) &&
               Objects.equals(email, other.email);
    }
}
