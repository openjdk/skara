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
package org.openjdk.skara.jcheck.iterators;

import java.util.Iterator;

public class FlatMapIterator<T> implements Iterator<T> {
    private final Iterator<Iterator<T>> ii;
    private Iterator<T> i;

    public FlatMapIterator(Iterator<Iterator<T>> ii) {
        this.ii = ii;
        this.i = null;
    }

    @Override
    public boolean hasNext() {
        if (i != null && i.hasNext()) {
            return true;
        }

        if (!ii.hasNext()) {
            return false;
        }

        i = ii.next();
        while (!i.hasNext() && ii.hasNext()) {
            i = ii.next();
        }

        return i.hasNext();
    }

    @Override
    public T next() {
        if (i == null) {
            return null;
        }

        return i.next();
    }
}
