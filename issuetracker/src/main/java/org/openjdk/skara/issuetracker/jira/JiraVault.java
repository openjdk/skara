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
package org.openjdk.skara.issuetracker.jira;

import org.openjdk.skara.network.RestRequest;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.logging.Logger;

class JiraVault {
    private final RestRequest request;
    private final String authId;
    private final Logger log = Logger.getLogger("org.openjdk.skara.issuetracker.jira");

    private String cookie;
    private Instant expires;

    private String checksum(String body) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(body.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().encodeToString(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Cannot find SHA-256");
        }
    }

    JiraVault(URI vaultUri, String vaultToken) {
        authId = checksum(vaultToken);
        request = new RestRequest(vaultUri, authId, () -> Arrays.asList(
                "X-Vault-Token", vaultToken
        ));
    }

    String getCookie() {
        if ((cookie == null) || Instant.now().isAfter(expires)) {
            var result = request.get("").execute();
            cookie = result.get("data").get("cookie.name").asString() + "=" + result.get("data").get("cookie.value").asString();
            expires = Instant.now().plus(Duration.ofSeconds(result.get("lease_duration").asInt()).dividedBy(2));
            log.info("Renewed Jira token (" + cookie + ") - expires " + expires);
        }
        return cookie;
    }

    String authId() {
        return authId;
    }
}
