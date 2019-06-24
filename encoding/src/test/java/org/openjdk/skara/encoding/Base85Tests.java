/*
 * Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.encoding;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class Base85Tests {
    @Test
    public void testGroupAndUngroup() {
        byte[] bytes = {0, 1, 2, 3};
        int g = Base85.group(bytes[0], bytes[1], bytes[2], bytes[3]);
        assertArrayEquals(bytes, Base85.ungroup(g));
    }

    @Test
    public void testGroupAndEncode() {
        byte[] bytes = {0, 1, 2, 3};
        int g = Base85.group(bytes[0], bytes[1], bytes[2], bytes[3]);
        byte[] encoded = Base85.encode(g);
        assertArrayEquals(new byte[]{48, 48, 57, 67, 54}, encoded);
    }

    @Test
    public void testEncodeAndDecode() {
        byte[] bytes = {0, 1, 2, 3};
        int g = Base85.group(bytes[0], bytes[1], bytes[2], bytes[3]);

        byte[] encoded = Base85.encode(g);
        int g2 = Base85.decode(encoded[0], encoded[1], encoded[2], encoded[3], encoded[4]);
        assertEquals(g, g2);
        assertArrayEquals(bytes, Base85.ungroup(g2));
    }

    @Test
    public void encodeAndDecodeUTF8String() {
        var s = "Hello Base85!";
        var bytes = s.getBytes(StandardCharsets.UTF_8);

        var encoded = Base85.encode(bytes);
        var decoded = Base85.decode(encoded, bytes.length);
        assertEquals(s, new String(decoded, StandardCharsets.UTF_8));
    }

    @Test
    public void encodeAndDecodeLongUTF8String() {
        var s = "Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt ut labore et dolore magna aliqua.";
        var bytes = s.getBytes(StandardCharsets.UTF_8);

        var encoded = Base85.encode(bytes);
        var decoded = Base85.decode(encoded, bytes.length);
        assertEquals(s, new String(decoded, StandardCharsets.UTF_8));
    }
}
