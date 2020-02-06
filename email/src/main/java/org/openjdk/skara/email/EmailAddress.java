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

import java.util.*;
import java.util.regex.Pattern;

public class EmailAddress {
    private String fullName;
    private String localPart;
    private String domain;

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        EmailAddress that = (EmailAddress) o;
        return Objects.equals(fullName, that.fullName) &&
                Objects.equals(localPart, that.localPart) &&
                Objects.equals(domain, that.domain);
    }

    @Override
    public int hashCode() {
        return Objects.hash(fullName, localPart, domain);
    }

    private EmailAddress(String fullName, String localPart, String domain) {
        this.fullName = fullName;
        this.localPart = localPart;
        this.domain = domain;
    }

    private final static Pattern decoratedAddressPattern = Pattern.compile("(?<name>.*?)(?:\\s*)<(?<local>.*)@(?<domain>.*?)>");
    private final static Pattern obfuscatedPattern = Pattern.compile("(?<local>.*) at (?<domain>.*) \\((?<name>.*)\\)");
    private final static Pattern plainAddressPattern = Pattern.compile("(?<name>)(?<local>.*)@(?<domain>.*?)");
    private final static Pattern unqualifiedDecoratedAddressPattern = Pattern.compile("(?<name>.*?)(?:\\s*)<(?<local>.*)(?<domain>)>");

    public static EmailAddress parse(String address) {
        var matcher = decoratedAddressPattern.matcher(address);
        if (!matcher.matches()) {
            matcher = obfuscatedPattern.matcher(address);
            if (!matcher.matches()) {
                matcher = plainAddressPattern.matcher(address);
                if (!matcher.matches()) {
                    matcher = unqualifiedDecoratedAddressPattern.matcher(address);
                    if (!matcher.matches()) {
                        throw new IllegalArgumentException("Cannot parse email address: " + address);
                    }
                }
            }
        }
        return new EmailAddress(matcher.group("name"), matcher.group("local"), matcher.group("domain"));
    }

    public static EmailAddress from(String fullName, String address) {
        return EmailAddress.parse(fullName + " <" + address + ">");
    }

    public static EmailAddress from(String address) {
        return EmailAddress.parse("<" + address + ">");
    }

    public Optional<String> fullName() {
        if ((fullName != null) && (fullName.length() > 0)) {
            return Optional.of(fullName);
        } else {
            return Optional.empty();
        }
    }

    public String address() {
        return localPart + "@" + domain;
    }

    public String localPart() {
        return localPart;
    }

    public String domain() {
        return domain;
    }

    @Override
    public String toString() {
        if (fullName().isPresent()) {
            return fullName().get() + " <" + address() + ">";
        } else {
            return "<" + address() + ">";
        }
    }

    public String toObfuscatedString() {
        var ret = localPart + " at " + domain;
        if (fullName().isPresent()) {
            ret += " (" + fullName + ")";
        }
        return ret;
    }
}
