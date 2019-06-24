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
package org.openjdk.skara.vcs.tools;

import java.nio.charset.StandardCharsets;
import java.io.*;
import java.util.Arrays;

public class UnixStreamReader {
    private final InputStream stream;

    private byte[] buffer;
    private String lastLine;

    public UnixStreamReader(InputStream stream) {
        this.stream = stream;
        this.buffer = new byte[128];
        this.lastLine = null;
    }

    public String readLine() throws IOException {
        var index = 0;
        var res = stream.read();
        while (res != -1) {
            if (res == (int) '\n') {
                lastLine = new String(buffer, 0, index, StandardCharsets.UTF_8);
                return lastLine;
            } else {
                if (index == buffer.length) {
                    buffer = Arrays.copyOf(buffer, buffer.length * 2);
                }
                buffer[index] = (byte) res;
                index++;
            }

            res = stream.read();
        }

        lastLine = null;
        return lastLine;
    }

    public byte[] read(int n) throws IOException {
        var result = new byte[n];
        read(result);
        return result;
    }

    public void read(byte[] b) throws IOException {
        var read = 0;
        while (read != b.length) {
            read += stream.read(b, read, b.length - read);
        }
    }

    public String lastLine() {
        return lastLine;
    }
}
