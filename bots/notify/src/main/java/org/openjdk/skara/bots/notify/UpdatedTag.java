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
package org.openjdk.skara.bots.notify;

import org.openjdk.skara.vcs.Tag;

import java.util.Objects;

public class UpdatedTag {
    private final Tag tag;
    private final String updater;
    private final boolean shouldRetry;

    public UpdatedTag(Tag tag, String updater, boolean shouldRetry) {
        this.tag = tag;
        this.updater = updater;
        this.shouldRetry = shouldRetry;
    }

    public Tag tag() {
        return tag;
    }

    public String updater() {
        return updater;
    }

    public boolean shouldRetry() {
        return shouldRetry;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        UpdatedTag that = (UpdatedTag) o;
        return shouldRetry == that.shouldRetry &&
                tag.equals(that.tag) &&
                updater.equals(that.updater);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tag, updater, shouldRetry);
    }
}
