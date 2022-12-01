/*
 * Copyright (c) 2020, 2021, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.bots.notify.issue;

import org.openjdk.skara.json.JSON;
import org.openjdk.skara.network.*;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.logging.Logger;

public class JbsVault {
    private final RestRequest request;
    private final String authId;
    private final URI authProbe;
    private static final Logger log = Logger.getLogger("org.openjdk.skara.bots.notify");

    private String cookie;

    private String checksum(String body) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(body.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().encodeToString(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Cannot find SHA-256");
        }
    }

    JbsVault(URI vaultUri, String vaultToken, URI jiraUri) {
        authId = checksum(vaultToken);
        request = new RestRequest(vaultUri, authId, (r) -> Arrays.asList(
                "X-Vault-Token", vaultToken
        ));
        this.authProbe = URIBuilder.base(jiraUri).appendPath("/rest/api/2/myself").build();
    }

    String getCookie() {
        if (cookie != null) {
            var authProbeRequest = new RestRequest(authProbe, authId, (r) -> Arrays.asList("Cookie", cookie));
            var res = authProbeRequest.get()
                                      .onError(error -> error.statusCode() >= 400 ? Optional.of(JSON.of("AUTH_ERROR")) : Optional.empty())
                                      .execute();
            if (res.isObject() && !res.contains("AUTH_ERROR")) {
                return cookie;
            }
        }

        // Renewal time
        var result = request.get("").execute();
        cookie = result.get("data").get("cookie.name").asString() + "=" + result.get("data").get("cookie.value").asString();
        log.info("Renewed Jira token (" + cookie + ")");
        return cookie;
    }

    String authId() {
        return authId;
    }
}
