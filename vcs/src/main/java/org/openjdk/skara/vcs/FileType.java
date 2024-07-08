/*
 * Copyright (c) 2018, 2024, Oracle and/or its affiliates. All rights reserved.
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

import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;
import java.util.Set;

public class FileType {
    private enum Type {
        DIRECTORY,
        REGULAR_NON_EXECUTABLE,
        REGULAR_NON_EXECUTABLE_GROUP_WRITABLE,
        REGULAR_EXECUTABLE,
        SYMBOLIC_LINK,
        VCS_LINK
    }

    private final Type type;

    private FileType(Type type) {
        this.type = type;
    }

    public static FileType fromOctal(String s) {
        switch (s) {
            case "040000":
                return new FileType(Type.DIRECTORY);
            case "100644":
                return new FileType(Type.REGULAR_NON_EXECUTABLE);
            case "100664":
                return new FileType(Type.REGULAR_NON_EXECUTABLE_GROUP_WRITABLE);
            case "100755":
                return new FileType(Type.REGULAR_EXECUTABLE);
            case "120000":
                return new FileType(Type.SYMBOLIC_LINK);
            case "160000":
                return new FileType(Type.VCS_LINK);
            case "000000":
                return null;
            default:
                throw new IllegalArgumentException("Unexpected octal file mode: " + s);
        }
    }

    public String toOctal() {
        switch (type) {
            case DIRECTORY:
                return "040000";
            case REGULAR_NON_EXECUTABLE:
                return "100644";
            case REGULAR_NON_EXECUTABLE_GROUP_WRITABLE:
                return "100664";
            case REGULAR_EXECUTABLE:
                return "100755";
            case SYMBOLIC_LINK:
                return "120000";
            case VCS_LINK:
                return "160000";
            default:
                throw new IllegalStateException("Unexpected type: " + type);
        }
    }

    public boolean isDirectory() {
        return type == Type.DIRECTORY;
    }

    public boolean isRegularNonExecutable() {
        return type == Type.REGULAR_NON_EXECUTABLE;
    }

    public boolean isRegular() {
        return type == Type.REGULAR_EXECUTABLE ||
               type == Type.REGULAR_NON_EXECUTABLE ||
               type == Type.REGULAR_NON_EXECUTABLE_GROUP_WRITABLE;
    }

    public boolean isGroupWritable() {
        return type == Type.REGULAR_NON_EXECUTABLE_GROUP_WRITABLE;
    }

    public boolean isExecutable() {
        return type == Type.REGULAR_EXECUTABLE;
    }

    public boolean isSymbolicLink() {
        return type == Type.SYMBOLIC_LINK;
    }

    public boolean isVCSLink() {
        return type == Type.VCS_LINK;
    }

    public boolean isLink() {
        return isSymbolicLink() || isVCSLink();
    }

    public Optional<Set<PosixFilePermission>> permissions() {
        switch (type) {
            case REGULAR_NON_EXECUTABLE:
                return Optional.of(PosixFilePermissions.fromString("rw-r--r--"));
            case REGULAR_NON_EXECUTABLE_GROUP_WRITABLE:
                return Optional.of(PosixFilePermissions.fromString("rw-rw-r--"));
            case REGULAR_EXECUTABLE:
                return Optional.of(PosixFilePermissions.fromString("rwxr-xr-x"));
            default:
                return Optional.empty();
        }
    }

    @Override
    public String toString() {
        return toOctal();
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof FileType ft)) {
            return false;
        }

        return type == ft.type;
    }

    @Override
    public int hashCode() {
        return type.hashCode();
    }
}
