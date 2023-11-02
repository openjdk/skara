/*
 * Copyright (c) 2018, 2023, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.host;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

public class HostUser {
    private String id;
    private String username;
    private String fullName;
    private String email;
    private boolean active;
    private boolean hasUpdated;
    private final Supplier<HostUser> supplier;

    public static class Builder {
        private String id;
        private String username;
        private String fullName;
        private String email;
        private boolean active = true;
        private Supplier<HostUser> supplier;

        public Builder id(int id) {
            this.id = String.valueOf(id);
            return this;
        }

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder username(String username) {
            this.username = username;
            return this;
        }

        public Builder fullName(String fullName) {
            this.fullName = fullName;
            return this;
        }

        public Builder email(String email) {
            this.email = email;
            return this;
        }

        public Builder active(boolean active) {
            this.active = active;
            return this;
        }

        public Builder supplier(Supplier<HostUser> supplier) {
            this.supplier = supplier;
            return this;
        }

        public HostUser build() {
            return new HostUser(id, username, fullName, email, active, supplier);
        }
    }

    private HostUser(String id, String username, String fullName, String email, boolean active, Supplier<HostUser> supplier) {
        this.id = id;
        this.username = username;
        this.fullName = fullName;
        this.email = email;
        this.active = active;
        this.hasUpdated = false;
        this.supplier = supplier;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static HostUser create(String id, String username, String fullName) {
        return builder().id(id).username(username).fullName(fullName).build();
    }

    public static HostUser create(String id, String username, String fullName, boolean active) {
        return builder().id(id).username(username).fullName(fullName).active(active).build();
    }

    public static HostUser create(int id, String username, String fullName) {
        return builder().id(id).username(username).fullName(fullName).build();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (other == null || getClass() != other.getClass()) {
            return false;
        }
        HostUser o = (HostUser) other;
        return Objects.equals(id(), o.id()) &&
               Objects.equals(username(), o.username());
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, username);
    }

    private void update() {
        if (hasUpdated) {
            return;
        }
        var result = supplier.get();
        id = result.id;
        username = result.username;
        fullName = result.fullName;
        email = result.email;
        active = result.active;
        hasUpdated = true;
    }

    public String id() {
        if (id == null) {
            update();
        }
        return id;
    }

    public String username() {
        if (username == null) {
            update();
        }
        return username;
    }

    public String fullName() {
        if (fullName == null) {
            update();
        }
        // If the user doesn't set full name, then use username instead
        if (fullName == null) {
            return username();
        }
        return fullName;
    }

    public Optional<String> email() {
        if (id == null || username == null || fullName == null) {
            update();
        }
        return Optional.ofNullable(email);
    }

    public boolean active() {
        return active;
    }

    @Override
    public String toString() {
        return "HostUserDetails{" +
                "id=" + id +
                ", username='" + username + '\'' +
                ", fullName='" + fullName + '\'' +
                '}';
    }

    public void changeUserName(String username) {
        this.username = username;
    }
}
