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
package org.openjdk.skara.storage;

import java.util.*;

public interface Storage<T> {
    /**
     * The current set of stored items. Concurrent changes to permanent storage may not be
     * detected until updates are attempted.
     * @return
     */
    Set<T> current();

    /**
     * Add new items and/or update existing ones. Flushes to permanent storage if needed. The
     * Storage instance may not be used concurrently, but the backing storage may have been updated
     * concurrently from a different instance. In that case the put operation will be retried.
     * @param item
     */
    void put(Collection<T> item);

    default void put(T item) {
        put(List.of(item));
    }
}
