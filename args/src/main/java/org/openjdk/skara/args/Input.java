/*
 * Copyright (c) 2018, 2019 Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.args;

public class Input {
    private final int position;
    private final String description;
    private final int occurrences;
    private final boolean required;

    Input(int position, String description, int occurrences, boolean required) {
        this.position = position;
        this.description = description;
        this.occurrences = occurrences;
        this.required = required;
    }

    public static InputDescriber position(int p) {
        return new InputDescriber(p);
    }

    public int getPosition() {
        return position;
    }

    public String getDescription() {
        return description;
    }

    public int getOccurrences() {
        return occurrences;
    }

    public boolean isTrailing() {
        return occurrences == -1;
    }

    public boolean isRequired() {
        return required;
    }

    @Override
    public String toString() {
        var builder = new StringBuilder();
        var n = isTrailing() ? 1 : occurrences;
        for (var i = 0; i < n; i++) {
            if (!isRequired()) {
                builder.append("[");
            }
            builder.append("<");
            builder.append(description);
            builder.append(">");
            if (!isRequired()) {
                builder.append("]");
            }
            if (i != (n - 1)) {
                builder.append(" ");
            }

            if (isTrailing()) {
                builder.append("...");
            }
        }

        return builder.toString();
    }
}
