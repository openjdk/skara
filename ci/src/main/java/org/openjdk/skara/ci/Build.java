/*
 * Copyright (c) 2019 Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.ci;

import java.util.Objects;

public class Build {
    public static enum OperatingSystem {
        WINDOWS,
        MACOS,
        LINUX,
        SOLARIS,
        AIX,
        FREEBSD,
        OPENBSD,
        NETBSD,
        HPUX,
        HAIKU
    }

    public static enum CPU {
        X86,
        X64,
        SPARCV9,
        AARCH64,
        AARCH32,
        PPCLE32,
        PPCLE64
    }

    public static enum DebugLevel {
        RELEASE,
        FASTDEBUG,
        SLOWDEBUG
    }

    private final OperatingSystem os;
    private final CPU cpu;
    private final DebugLevel debugLevel;

    public Build(OperatingSystem os, CPU cpu, DebugLevel debugLevel) {
        this.os = os;
        this.cpu = cpu;
        this.debugLevel = debugLevel;
    }

    public OperatingSystem os() {
        return os;
    }

    public CPU cpu() {
        return cpu;
    }

    public DebugLevel debugLevel() {
        return debugLevel;
    }

    @Override
    public String toString() {
        return os.toString().toLowerCase() + "-" +
               cpu.toString().toLowerCase() + "-" +
               debugLevel.toString().toLowerCase();
    }

    @Override
    public int hashCode() {
        return Objects.hash(os, cpu, debugLevel);
    }

    @Override
    public boolean equals(Object other) {
        if (other == this) {
            return true;
        }

        if (!(other instanceof Build)) {
            return false;
        }

        var o = (Build) other;
        return Objects.equals(os, o.os) &&
               Objects.equals(cpu, o.cpu) &&
               Objects.equals(debugLevel, o.debugLevel);
    }
}
