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
package org.openjdk.skara.host.github;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

public class GitHubApplicationTests {

    @Test
    public void tokenSetSimple() {
        Token t = new Token(() -> "a", Duration.ofHours(1));
        assertEquals("a", t.toString());
    }

    private final String[] sequence = {"a", "b", "c"};
    private int sequenceIndex = 0;
    private String sequenceGenerator() {
        return sequence[sequenceIndex++];
    }

    @Test
    public void tokenCache() {
        sequenceIndex = 0;
        Token t = new Token(this::sequenceGenerator, Duration.ofHours(1));
        assertEquals("a", t.toString());
        assertEquals("a", t.toString());
    }

    @Test
    public void tokenExpiration() {
        sequenceIndex = 0;
        Token t = new Token(this::sequenceGenerator, Duration.ZERO);
        assertEquals("a", t.toString());
        assertEquals("b", t.toString());
    }

    private String badGenerator() throws Token.GeneratorError {
        throw new Token.GeneratorError("error");
    }

    @Test
    public void tokenGeneratorError() {
        Token t = new Token(this::badGenerator, Duration.ZERO);
        assertThrows(GitHubApplicationError.class, () -> t.toString());
    }

}
