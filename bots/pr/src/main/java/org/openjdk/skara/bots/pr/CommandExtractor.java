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
package org.openjdk.skara.bots.pr;

import org.openjdk.skara.host.HostUser;

import java.util.*;
import java.util.regex.Pattern;

public class CommandExtractor {
    private static final Pattern commandPattern = Pattern.compile("^\\s*/([A-Za-z]+)(?:\\s+(.*))?");

    private static String formatId(String baseId, int subId) {
        if (subId > 0) {
            return String.format("%s:%d", baseId, subId);
        } else {
            return baseId;
        }
    }

    static List<CommandInvocation> extractCommands(Map<String, CommandHandler> commandHandlers, String text, String baseId, HostUser user) {
        var ret = new ArrayList<CommandInvocation>();
        CommandHandler multiLineHandler = null;
        List<String> multiLineBuffer = null;
        String multiLineCommand = null;
        int subId = 0;
        for (var line : text.split("\\R")) {
            var commandMatcher = commandPattern.matcher(line);
            if (commandMatcher.matches()) {
                if (multiLineHandler != null) {
                    ret.add(new CommandInvocation(formatId(baseId, subId++), user, multiLineHandler, multiLineCommand, String.join("\n", multiLineBuffer)));
                    multiLineHandler = null;
                }
                var command = commandMatcher.group(1).toLowerCase();
                var handler = commandHandlers.get(command);
                if (handler != null && handler.multiLine()) {
                    multiLineHandler = handler;
                    multiLineBuffer = new ArrayList<>();
                    if (commandMatcher.group(2) != null) {
                        multiLineBuffer.add(commandMatcher.group(2));
                    }
                    multiLineCommand = command;
                } else {
                    ret.add(new CommandInvocation(formatId(baseId, subId++), user, handler, command, commandMatcher.group(2)));
                }
            } else {
                if (multiLineHandler != null) {
                    multiLineBuffer.add(line);
                }
            }
        }
        if (multiLineHandler != null) {
            ret.add(new CommandInvocation(formatId(baseId, subId), user, multiLineHandler, multiLineCommand, String.join("\n", multiLineBuffer)));
        }
        return ret;
    }

}
