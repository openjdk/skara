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

import org.openjdk.skara.bot.BotConfiguration;
import org.openjdk.skara.json.JSONObject;

import java.util.*;
import java.util.stream.*;

public interface NotifierFactory {
    /**
     * A user-friendly name for the given notifier, used for configuration section naming. Should be lower case.
     * @return
     */
    String name();

    /**
     * Creates instances of this notifier according to the provided configuration.
     * @return
     */
    Notifier create(BotConfiguration botConfiguration, JSONObject notifierConfiguration);

    static List<NotifierFactory> getNotifierFactories() {
        return StreamSupport.stream(ServiceLoader.load(NotifierFactory.class).spliterator(), false)
                .collect(Collectors.toList());
    }
}
