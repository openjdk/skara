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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.openjdk.skara.jcheck.SizeUtils.*;

public class SizeUtilsTest {
    @Test
    void testGetSizeFromString() {
        String[] simpleLowerStrList = new String[]{"1", "2b", "3k", "4m", "5g", "", "a", "ab", "ak", "am", "ag"};
        String[] simpleUpperStrList = new String[]{"1", "2B", "3K", "4M", "5G", "", "A", "AB", "AK", "AM", "AG"};
        long[] simpleSizeList = new long[]{1L, 2L, 3L * 1024, 4L * 1024 * 1024, 5L * 1024 * 1024 * 1024, 0, 0, 0, 0, 0, 0};
        for (int i = 0; i < simpleSizeList.length; i++) {
            assertEquals(simpleSizeList[i], getSizeFromString(simpleLowerStrList[i]));
            assertEquals(simpleSizeList[i], getSizeFromString(simpleUpperStrList[i]));
        }

        String[] kiloStrList = new String[]{"1kb", "2Kb", "3kB", "4KB", "akb", "aKb", "akB", "aKB", "kb", "Kb", "kB", "KB"};
        String[] mageStrList = new String[]{"1mb", "2Mb", "3mB", "4MB", "amb", "aMb", "amB", "aMB", "mb", "Mb", "mB", "MB"};
        String[] gigaStrList = new String[]{"1gb", "2Gb", "3gB", "4GB", "agb", "aGb", "agB", "aGB", "gb", "Gb", "gB", "GB"};
        long[] expectedSizeList = new long[]{1L, 2L, 3L, 4L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L};
        for (int i = 0; i < expectedSizeList.length; i++) {
            assertEquals(expectedSizeList[i] * 1024, getSizeFromString(kiloStrList[i]));
            assertEquals(expectedSizeList[i] * 1024 * 1024, getSizeFromString(mageStrList[i]));
            assertEquals(expectedSizeList[i] * 1024 * 1024 * 1024, getSizeFromString(gigaStrList[i]));
        }
    }
}
