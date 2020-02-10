/*
 * Copyright (c) 2019, 2020, Oracle and/or its affiliates. All rights reserved.
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

import static org.junit.jupiter.api.Assertions.*;

class EmailAddressTests {
    @Test
    void simple() {
        var address = EmailAddress.parse("Full Name <full@name.com>");
        assertEquals("Full Name", address.fullName().orElseThrow());
        assertEquals("full@name.com", address.address());
        assertEquals("name.com", address.domain());
        assertEquals("full", address.localPart());
    }

    @Test
    void noFullName() {
        var address = EmailAddress.parse("<no@name.com>");
        assertFalse(address.fullName().isPresent());
        assertEquals("no@name.com", address.address());
        assertEquals("name.com", address.domain());
        assertEquals("no", address.localPart());
    }

    @Test
    void noBrackets() {
        var address = EmailAddress.parse("no@brackets.com");
        assertFalse(address.fullName().isPresent());
        assertEquals("no@brackets.com", address.address());
        assertEquals("brackets.com", address.domain());
        assertEquals("no", address.localPart());
    }

    @Test
    void noDomain() {
        var address = EmailAddress.parse("<noone.ever.>");
        assertFalse(address.fullName().isPresent());
        assertEquals("noone.ever.@", address.address());
        assertEquals("", address.domain());
        assertEquals("noone.ever.", address.localPart());
    }
}
