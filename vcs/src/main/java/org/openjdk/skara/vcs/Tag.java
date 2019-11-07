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

import java.util.Objects;
import java.util.Optional;
import java.time.ZonedDateTime;

public class Tag {
    public static class Annotated {
        private final String name;
        private final Hash target;
        private final Author author;
        private final ZonedDateTime date;
        private final String message;

        public Annotated(String name, Hash target, Author author, ZonedDateTime date, String message) {
            this.name = name;
            this.target = target;
            this.author = author;
            this.date = date;
            this.message = message;
        }

        public String name() {
            return name;
        }

        public Hash target() {
            return target;
        }

        public Author author() {
            return author;
        }

        public ZonedDateTime date() {
            return date;
        }

        public String message() {
            return message;
        }

        @Override
        public boolean equals(Object other) {
            if (other == this) {
                return true;
            }

            if (!(other instanceof Annotated)) {
                return false;
            }

            var o = (Annotated) other;
            return Objects.equals(name, o.name) &&
                   Objects.equals(target, o.target) &&
                   Objects.equals(author, o.author) &&
                   Objects.equals(date, o.date) &&
                   Objects.equals(message, o.message);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, target, author, date, message);
        }

        @Override
        public String toString() {
            return name + " -> " + target.hex();
        }
    }

    private final String name;

    public Tag(String name) {
        this.name = name;
    }

    public String name() {
        return name;
    }

    @Override
    public String toString() {
        return name;
    }

    @Override
    public int hashCode() {
        return Objects.hash(name);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Tag)) {
            return false;
        }

        var other = (Tag) o;
        return Objects.equals(name, other.name);
    }
}
