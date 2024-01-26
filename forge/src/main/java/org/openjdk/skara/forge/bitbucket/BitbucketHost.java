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
package org.openjdk.skara.forge.bitbucket;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import org.openjdk.skara.forge.Forge;
import org.openjdk.skara.forge.HostedCommit;
import org.openjdk.skara.forge.HostedRepository;
import org.openjdk.skara.forge.MemberState;
import org.openjdk.skara.host.Credential;
import org.openjdk.skara.host.HostUser;
import org.openjdk.skara.vcs.Hash;

public class BitbucketHost implements Forge {
    public static final int DEFAULT_SSH_PORT = 22;
    private final String name;
    private final URI uri;
    private final boolean useSsh;
    private final int sshPort;
    private final Credential credential;

    public BitbucketHost(String name, URI uri, boolean useSsh, int sshPort, Credential credential) {
        this.name = name;
        this.uri = uri;
        this.useSsh = useSsh;
        this.sshPort = sshPort;
        this.credential = credential;
    }

    @Override
    public String name() {
        return name;
    }

    public URI getUri() {
        return uri;
    }

    public String sshHostString() {
        if (credential == null) {
            throw new IllegalStateException("Cannot use ssh without user name");
        }
        return credential.username() + "." + uri.getHost() + ((sshPort != DEFAULT_SSH_PORT) ? ":" + sshPort : "");
    }

    boolean useSsh() {
        return useSsh;
    }

    Optional<Credential> getCredential() {
        return Optional.ofNullable(credential);
    }

    @Override
    public Optional<HostedRepository> repository(String name) {
        return Optional.of(new BitbucketRepository(this, name));
    }

    @Override
    public Optional<String> search(Hash hash, boolean includeDiffs) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isValid() {
        return true;
    }

    @Override
    public Optional<HostUser> user(String username) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Optional<HostUser> userById(String username) {
        throw new UnsupportedOperationException();
    }

    @Override
    public HostUser currentUser() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isMemberOf(String groupId, HostUser user) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String hostname() {
        return uri.getHost();
    }

    @Override
    public List<HostUser> groupMembers(String group) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void addGroupMember(String group, HostUser user) {
        throw new UnsupportedOperationException();
    }

    @Override
    public MemberState groupMemberState(String group, HostUser user) {
        throw new UnsupportedOperationException();
    }
}
