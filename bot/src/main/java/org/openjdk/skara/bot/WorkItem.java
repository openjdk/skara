/*
 * Copyright (c) 2018, 2019 Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.bot;

import java.nio.file.Path;
import java.util.*;

public interface WorkItem {
    /**
     * Return true if this item can run concurrently with <code>other</code>, otherwise false.
     * @param other
     * @return
     */
    boolean concurrentWith(WorkItem other);

    /**
     * Execute the appropriate tasks with the provided scratch folder. Optionally return follow-up work items
     * that will be scheduled for execution.
     * @param scratchPath
     * @return A collection of follow-up work items, allowed to be empty (or null) if none are needed.
     */
    Collection<WorkItem> run(Path scratchPath);

    /**
     * The BotRunner will catch <code>RuntimeException</code>s, implementing this method allows a WorkItem to
     * perform additional cleanup if necessary (avoiding the need for catching and rethrowing the exception).
     * @param e
     */
    default void handleRuntimeException(RuntimeException e) {}
}
