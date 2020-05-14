/*
 * Copyright (c) 2020, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.bots.mlbridge;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class QuoteFilterTests {
    @Test
    void simple() {
        assertEquals("a\nb", QuoteFilter.stripLinkBlock("a\nb", URI.create("http://test")));
        assertEquals("a", QuoteFilter.stripLinkBlock("a\n> b\n> http://test", URI.create("http://test")));
        assertEquals("a\nc", QuoteFilter.stripLinkBlock("a\n> b\n> http://test\nc", URI.create("http://test")));
        assertEquals("a\nc", QuoteFilter.stripLinkBlock("a\n> > b\n> http://test\nc", URI.create("http://test")));
        assertEquals("a\n> b\nc", QuoteFilter.stripLinkBlock("a\n> b\n> > http://test\nc", URI.create("http://test")));
        assertEquals("a\n> b\nc", QuoteFilter.stripLinkBlock("a\n> b\n> > http://test\n> > d\nc", URI.create("http://test")));
    }

    @Test
    void notQuoted() {
        assertEquals("a\nhttp://test", QuoteFilter.stripLinkBlock("a\nhttp://test", URI.create("http://test")));
    }

    @Test
    void trailingSpace() {
        assertEquals("a\nc", QuoteFilter.stripLinkBlock("a\n>> b\n>>\n>> http://test\nc", URI.create("http://test")));
    }

}
