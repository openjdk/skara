/*
 * Copyright (c) 2023, 2024, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.forge.internal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

public class ForgeUtils {

    /**
     * Adds a special ssh key configuration in the user's ssh config file.
     * The config will only apply to the fake host userName.hostName so should
     * not interfere with other user configurations. The caller of this method
     * needs to use userName.hostName as host name when calling ssh.
     */
    public static void configureSshKey(String userName, String hostName, String sshKeyFile) {
        var cfgPath = Path.of(System.getProperty("user.home"), ".ssh");
        if (!Files.isDirectory(cfgPath)) {
            try {
                Files.createDirectories(cfgPath);
            } catch (IOException ignored) {
            }
        }

        var cfgFile = cfgPath.resolve("config");
        var existing = "";
        try {
            existing = Files.readString(cfgFile);
        } catch (IOException ignored) {
        }

        var userHost = userName + "." + hostName;
        var existingBlock = Pattern.compile("^Match host " + Pattern.quote(userHost) + "(?:\\R[ \\t]+.*)+", Pattern.MULTILINE);
        var existingMatcher = existingBlock.matcher(existing);
        var filtered = existingMatcher.replaceAll("");
        var result = "Match host " + userHost + "\n" +
                "  Hostname " + hostName + "\n" +
                "  PreferredAuthentications publickey\n" +
                "  StrictHostKeyChecking no\n" +
                "  IdentityFile " + sshKeyFile + "\n" +
                "\n";

        try {
            Files.writeString(cfgFile, result + filtered.strip() + "\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
