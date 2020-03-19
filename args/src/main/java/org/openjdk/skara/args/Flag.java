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

import java.util.Objects;

public class Flag {
    private boolean isSwitch;
    private final String shortcut;
    private final String fullname;
    private final String description;
    private final String helptext;
    private final boolean isRequired;

    Flag(boolean isSwitch, String shortcut, String fullname, String description, String helptext, boolean isRequired) {
        this.isSwitch = isSwitch;
        this.shortcut = shortcut;
        this.fullname = fullname;
        this.description = description;
        this.helptext = helptext;
        this.isRequired = isRequired;
    }

    boolean isSwitch() {
        return isSwitch;
    }

    public String fullname() {
        return fullname;
    }

    public String shortcut() {
        return shortcut;
    }

    public String description() {
        return description;
    }

    public String helptext() {
        return helptext;
    }

    boolean isRequired() {
        return isRequired;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Flag)) {
            return false;
        }

        Flag other = (Flag) o;
        return Objects.equals(isSwitch, other.isSwitch) &&
               Objects.equals(shortcut, other.shortcut) &&
               Objects.equals(fullname, other.fullname) &&
               Objects.equals(helptext, other.helptext) &&
               Objects.equals(isRequired, other.isRequired);
    }

    @Override
    public int hashCode() {
        return Objects.hash(isSwitch,
                            shortcut,
                            fullname,
                            helptext,
                            isRequired);
    }

    @Override
    public String toString() {
        if (shortcut.equals("")) {
            return "--" + fullname;
        }

        if (fullname.equals("")) {
            return "-" + shortcut;
        }

        return "-" + shortcut + ", --" + fullname;
    }
}
