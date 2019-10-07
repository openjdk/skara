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
package org.openjdk.skara.host.network;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class URIBuilderTests {
    private final String validHost = "http://www.test.com";

    @Test
    void setPathSimple() {
        var a = URIBuilder.base(validHost).setPath("/a").build();
        var b = URIBuilder.base(validHost).setPath("/b").build();
        var aToB = URIBuilder.base(a).setPath("/b").build();

        assertEquals("www.test.com", a.getHost());
        assertEquals("/a", a.getPath());
        assertEquals("/b", b.getPath());
        assertEquals("/b", aToB.getPath());
    }

    @Test
    void appendPathSimple() {
        var a = URIBuilder.base(validHost).setPath("/a").build();
        var aPlusB = URIBuilder.base(a).appendPath("/b").build();

        assertEquals("/a", a.getPath());
        assertEquals("/a/b", aPlusB.getPath());
    }

    @Test
    void invalidBase() {
        assertThrows(URIBuilderException.class,
                () -> URIBuilder.base("x:\\y").build());
    }

    @Test
    void invalidSetPath() {
        assertThrows(URIBuilderException.class,
                () -> URIBuilder.base(validHost).setPath("\\c").build());
    }

    @Test
    void invalidAppendPath() {
        assertThrows(URIBuilderException.class,
                () -> URIBuilder.base(validHost).appendPath("\\c").build());
    }

    @Test
    void noHost() {
        var a = URIBuilder.base("file:///a/b/c").build();
        assertEquals("/a/b/c", a.getPath());
    }
}
