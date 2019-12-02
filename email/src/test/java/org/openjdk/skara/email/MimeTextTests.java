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
package org.openjdk.skara.email;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MimeTextTests {
    @Test
    void simple() {
        var encoded = "=?UTF-8?B?w6XDpMO2?=";
        var decoded = "åäö";
        assertEquals(encoded, MimeText.encode(decoded));
        assertEquals(decoded, MimeText.decode(encoded));
    }

    @Test
    void mixed() {
        var encoded = "=?UTF-8?B?VMOpc3Q=?=";
        var decoded = "Tést";
        assertEquals(encoded, MimeText.encode(decoded));
        assertEquals(decoded, MimeText.decode(encoded));
    }

    @Test
    void multipleWords() {
        var encoded = "This is a =?UTF-8?B?dMOpc3Q=?= of =?UTF-8?B?bcO8bHRpcGxl?= words";
        var decoded = "This is a tést of mültiple words";
        assertEquals(encoded, MimeText.encode(decoded));
        assertEquals(decoded, MimeText.decode(encoded));
    }

    @Test
    void concatenateTokens() {
        var encoded = "=?UTF-8?B?VMOpc3Q=?= =?UTF-8?B?IA==?= =?UTF-8?B?VMOpc3Q=?=";
        var decoded = "Tést Tést";
        assertEquals(encoded, MimeText.encode(decoded));
        assertEquals(decoded, MimeText.decode(encoded));
    }

    @Test
    void preserveSpaces() {
        var encoded = "spac  es";
        var decoded = "spac  es";
        assertEquals(encoded, MimeText.encode(decoded));
        assertEquals(decoded, MimeText.decode(encoded));
    }

    @Test
    void decodeSpaces() {
        var encoded = "=?UTF-8?B?VMOpc3Q=?=   =?UTF-8?B?VMOpc3Q=?=   and  ";
        var decoded = "TéstTést   and  ";
        assertEquals(decoded, MimeText.decode(encoded));
    }

    @Test
    void decodeIsoQ() {
        assertEquals("Bä", MimeText.decode("=?iso-8859-1?Q?B=E4?="));
    }

    @Test
    void decodeIsoQSpaces() {
        assertEquals("Bä Bä Bä", MimeText.decode("=?iso-8859-1?Q?B=E4_B=E4=20B=E4?="));
    }
}
