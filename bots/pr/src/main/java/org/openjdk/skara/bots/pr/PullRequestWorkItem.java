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
package org.openjdk.skara.bots.pr;

import org.openjdk.skara.bot.WorkItem;
import org.openjdk.skara.forge.PullRequest;

import java.util.function.Consumer;

abstract class PullRequestWorkItem implements WorkItem {
    final Consumer<RuntimeException> errorHandler;
    final PullRequestBot bot;
    final PullRequest pr;

    PullRequestWorkItem(PullRequestBot bot, PullRequest pr, Consumer<RuntimeException> errorHandler) {
        this.bot = bot;
        this.pr = pr;
        this.errorHandler = errorHandler;
    }

    @Override
    public final boolean concurrentWith(WorkItem other) {
        if (!(other instanceof PullRequestWorkItem)) {
            return true;
        }
        PullRequestWorkItem otherItem = (PullRequestWorkItem)other;
        if (!pr.id().equals(otherItem.pr.id())) {
            return true;
        }
        if (!pr.repository().name().equals(otherItem.pr.repository().name())) {
            return true;
        }
        return false;
    }

    @Override
    public final void handleRuntimeException(RuntimeException e) {
        errorHandler.accept(e);
    }
}
