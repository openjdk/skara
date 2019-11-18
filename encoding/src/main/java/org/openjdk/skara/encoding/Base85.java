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

public class Base85 {
    private static int BASE = 85;

    private static byte[] ENCODE = {
        '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
        'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J',
        'K', 'L', 'M', 'N', 'O', 'P', 'Q', 'R', 'S', 'T',
        'U', 'V', 'W', 'X', 'Y', 'Z', 'a', 'b', 'c', 'd',
        'e', 'f', 'g', 'h', 'i', 'j', 'k', 'l', 'm', 'n',
        'o', 'p', 'q', 'r', 's', 't', 'u', 'v', 'w', 'x',
        'y', 'z', '!', '#', '$', '%', '&', '(', ')', '*',
        '+', '-', ';', '<', '=', '>', '?', '@', '^', '_',
        '`', '{', '|', '}', '~'
    };

    private static final byte[] DECODE = new byte[128];
    static {
        for (byte i = 0; i < ENCODE.length; i++) {
            DECODE[ENCODE[i]] = i;
        }
    }

    private static int div(int dividend, int divisor) {
        return Integer.divideUnsigned(dividend, divisor);
    }

    private static int rem(int dividend, int divisor) {
        return Integer.remainderUnsigned(dividend, divisor);
    }

    static int group(byte a, byte b, byte c, byte d) {
        int g = 0;
        g |= a << 24;
        g |= b << 16;
        g |= c << 8;
        g |= d;
        return g;
    }

    static byte[] ungroup(int g) {
        byte[] bytes = new byte[4];
        bytes[0] = (byte) ((g & 0xFF000000) >> 24);
        bytes[1] = (byte) ((g & 0x00FF0000) >> 16);
        bytes[2] = (byte) ((g & 0x0000FF00) >> 8);
        bytes[3] = (byte) ((g & 0x000000FF));
        return bytes;
    }

    static byte[] encode(int g) {
        byte[] bytes = new byte[5];

        bytes[4] = ENCODE[rem(g, BASE)];

        g = div(g, BASE);
        bytes[3] = ENCODE[rem(g, BASE)];

        g = div(g, BASE);
        bytes[2] = ENCODE[rem(g, BASE)];

        g = div(g, BASE);
        bytes[1] = ENCODE[rem(g, BASE)];

        g = div(g, BASE);
        bytes[0] = ENCODE[rem(g, BASE)];

        return bytes;
    }

    static int decode(byte a, byte b, byte c, byte d, byte e) {
        int g = 0;

        g = DECODE[a];

        g *= BASE;
        g += DECODE[b];

        g *= BASE;
        g += DECODE[c];

        g *= BASE;
        g += DECODE[d];

        g *= BASE;
        g += DECODE[e];

        return g;
    }

    public static byte[] encode(byte[] src) {
        int r = rem(src.length, 4);
        int n = div(src.length, 4);
        byte[] ascii = new byte[(n * 5) + (r == 0 ? 0 : 5)];

        int pos = 0;
        for (int i = 0; i < (n * 4); i += 4) {
            int g = group(src[i], src[i + 1], src[i + 2], src[i + 3]);
            byte[] bytes = encode(g);
            for (int bi = 0; bi < 5; bi++) {
                ascii[pos + bi] = bytes[bi];
            }
            pos += 5;
        }

        if (r > 0) {
            int g = group(src[n * 4], r > 1 ? src[n * 4 + 1] : 0, r > 2 ? src[n * 4 + 2] : 0, (byte) 0);
            byte[] bytes = encode(g);
            for (int bi = 0; bi < 5; bi++) {
                ascii[pos + bi] = bytes[bi];
            }
        }

        return ascii;
    }


    public static byte[] decode(byte[] src, int numBytes) {
        byte[] data = new byte[numBytes];
        int pos = 0;

        int r = rem(numBytes, 4);
        int last = r == 0 ? 0 : 5;
        for (int i = 0; i < (src.length - last); i += 5) {
            int g = decode(src[i], src[i + 1], src[i + 2], src[i + 3], src[i + 4]);
            byte[] bytes = ungroup(g);
            for (int bi = 0; bi < 4; bi++) {
                data[pos + bi] = bytes[bi];
            }
            pos += 4;
        }

        if (r > 0) {
            int n = src.length;
            int g = decode(src[n - 5], src[n - 4], src[n - 3], src[n - 2], src[n - 1]);
            byte[] bytes = ungroup(g);
            for (int bi = 0; bi < r; bi++) {
                data[pos + bi] = bytes[bi];
            }
        }

        return data;
    }
}
