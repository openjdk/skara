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
package org.openjdk.skara.vcs.openjdk.convert;

import org.openjdk.skara.vcs.*;

import java.io.*;
import java.util.*;
import java.nio.charset.StandardCharsets;

class Pipe {
    private final InputStream from;
    private final OutputStream to;
    private final byte[] lineBuffer;

    Pipe(InputStream from, OutputStream to, int bufferSize) {
        this.from = from;
        this.to = to;
        lineBuffer = new byte[bufferSize];
    }

    int read() throws IOException {
        return from.read();
    }

    byte[] read(int n) throws IOException {
        var result = new byte[n];
        read(result);
        return result;
    }

    void read(byte[] b) throws IOException {
        var read = 0;
        while (read != b.length) {
            read += from.read(b, read, b.length - read);
        }
    }

    int read(byte[] b, int offset, int length) throws IOException {
        return from.read(b, offset, length);
    }

    String readln() throws IOException {
        var index = 0;
        var current = from.read();
        while (current != (int) '\n') {
            if (index == lineBuffer.length) {
                throw new IOException("Line too long: " + new String(lineBuffer, 0, index, StandardCharsets.UTF_8));
            }
            lineBuffer[index] = (byte) current;
            index++;
            current = from.read();
        }
        return new String(lineBuffer, 0, index, StandardCharsets.UTF_8);
    }

    void print(String s) throws IOException {
        to.write(s.getBytes(StandardCharsets.UTF_8));
    }

    void print(long l) throws IOException {
        print(Long.toString(l));
    }

    void println(String s) throws IOException {
        print(s);
        print("\n");
        to.flush();
    }

    void print(byte[] bytes) throws IOException {
        to.write(bytes);
    }

    void print(byte[] bytes, int offset, int length) throws IOException {
        to.write(bytes, offset, length);
    }

    void println(byte[] bytes) throws IOException {
        print(bytes);
        print("\n");
        to.flush();
    }

    void println(int i) throws IOException {
        println(Integer.toString(i));
    }
}
