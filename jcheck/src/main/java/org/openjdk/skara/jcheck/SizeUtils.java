/*
 * Copyright (c) 2021, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.jcheck;

import java.util.logging.Logger;

public class SizeUtils {
    private static final Logger log = Logger.getLogger("org.openjdk.skara.jcheck.sizeutils");

    public static long getSizeFromString(String str) {
        long size = 0;
        String unit = "";
        var sizeStr = str.toLowerCase();
        if (sizeStr.endsWith("kb") || sizeStr.endsWith("mb") || sizeStr.endsWith("gb")) {
            unit = sizeStr.substring(sizeStr.length() - 2);
            sizeStr = sizeStr.substring(0, sizeStr.length() - 2);
        } else if (sizeStr.endsWith("k") || sizeStr.endsWith("m") || sizeStr.endsWith("g") || sizeStr.endsWith("b")) {
            unit = sizeStr.substring(sizeStr.length() - 1);
            sizeStr = sizeStr.substring(0, sizeStr.length() - 1);
        }
        try {
            size = Long.parseLong(sizeStr);
        } catch (NumberFormatException exception) {
            log.info("The string '" + str + "' can't be convert to a number. " + exception);
            size = 0; // default 0
        }
        switch (unit) {
            case "kb", "k" -> size *= 1024;
            case "mb", "m" -> size *= 1024 * 1024;
            case "gb", "g" -> size *= 1024 * 1024 * 1024;
        }
        return size;
    }
}
