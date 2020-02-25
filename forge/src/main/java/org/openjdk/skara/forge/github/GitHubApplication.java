/*
 * Copyright (c) 2018, 2019, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.forge.github;

import org.openjdk.skara.json.*;
import org.openjdk.skara.network.URIBuilder;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.*;
import java.time.*;
import java.util.Base64;
import java.util.logging.Logger;
import java.util.regex.Pattern;

class GitHubApplicationError extends RuntimeException {
    GitHubApplicationError(String msg) {
        super(msg);
    }
}

class Token {

    static class GeneratorError extends Exception {
        public GeneratorError(String message) { super(message); }
    }

    public interface TokenGenerator {
        String generate() throws GeneratorError;
    }

    private final TokenGenerator generator;
    private final Duration expiration;
    private String cached;
    private Instant generatedAt;

    Token(TokenGenerator generator, Duration expiration) {
        this.generator = generator;
        this.expiration = expiration;
    }

    public void expire() {
        generatedAt = null;
    }

    @Override
    public String toString() {

        if (generatedAt != null) {
            if (generatedAt.plus(expiration).isAfter(Instant.now())) {
                return cached;
            }
        }

        try {
            cached = generator.generate();
            generatedAt = Instant.now();
            return cached;
        } catch (GeneratorError generatorError) {
            // FIXME? The operation could be retried here
            throw new GitHubApplicationError("Failed to generate authentication token (" + generatorError.getMessage() + ")");
        }
    }
}

public class GitHubApplication {
    private final String issue;
    private final String id;

    private final URI apiBase;
    private final PrivateKey key;
    private final Token jwt;
    private final Token installationToken;

    private final Logger log;

    static class GitHubConfigurationError extends RuntimeException {
        public GitHubConfigurationError(String message) {
            super(message);
        }
    }

    public GitHubApplication(String key, String issue, String id) {

        log = Logger.getLogger("org.openjdk.host.github");

        apiBase = URIBuilder.base("https://api.github.com/").build();
        this.issue = issue;
        this.id = id;

        this.key = loadPkcs8PemFromString(key);
        jwt = new Token(this::generateJsonWebToken, Duration.ofMinutes(5));
        installationToken = new Token(this::generateInstallationToken, Duration.ofMinutes(30));
    }

    private PrivateKey loadPkcs8PemFromString(String pem) {
        try {
            var pemPattern = Pattern.compile("^-*BEGIN PRIVATE KEY-*$(.*)^-*END PRIVATE KEY-*",
                    Pattern.DOTALL | Pattern.MULTILINE);
            var keyString = pemPattern.matcher(pem).replaceFirst("$1");
            //keyString = keyString.replace("\n", "");
            var rawKey = Base64.getMimeDecoder().decode(keyString);
            var factory = KeyFactory.getInstance("RSA");
            return factory.generatePrivate(new PKCS8EncodedKeySpec(rawKey));
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new GitHubConfigurationError("Unable to load private key (" + e + ")");
        }
    }

    private String generateJsonWebToken() {
        var issuedAt = ZonedDateTime.now(ZoneOffset.UTC);
        var expires = issuedAt.plus(Duration.ofMinutes(8));

        var header = Base64.getUrlEncoder().encode(JSON.object()
                                                       .put("alg", "RS256")
                                                       .put("typ", "JWT")
                                                       .toString().getBytes(StandardCharsets.UTF_8));
        var claims = Base64.getUrlEncoder().encode(JSON.object()
                .put("iss", issue)
                .put("iat", (int)issuedAt.toEpochSecond())
                .put("exp", (int)expires.toEpochSecond())
                .toString().getBytes(StandardCharsets.UTF_8));
        var separator = ".".getBytes(StandardCharsets.UTF_8);

        try {
            var signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(key);
            var payload = new ByteArrayOutputStream();
            payload.write(header);
            payload.write(separator);
            payload.write(claims);
            signer.update(payload.toByteArray());
            var signature = Base64.getUrlEncoder().encode(signer.sign());

            var token = new ByteArrayOutputStream();
            token.write(header);
            token.write(separator);
            token.write(claims);
            token.write(separator);
            token.write(signature);

            return token.toString(StandardCharsets.US_ASCII);
        } catch (NoSuchAlgorithmException | SignatureException e) {
            throw new RuntimeException(e);
        } catch (InvalidKeyException e) {
            throw new GitHubConfigurationError("Invalid private key");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String generateInstallationToken() throws Token.GeneratorError {
        var tokens = URIBuilder.base(apiBase).setPath("/installations/" + id + "/access_tokens").build();
        var client = HttpClient.newBuilder()
                               .connectTimeout(Duration.ofSeconds(10))
                               .build();

        try {
            var response = client.send(
                    HttpRequest.newBuilder()
                               .uri(tokens)
                               .timeout(Duration.ofSeconds(30))
                               .header("Authorization", "Bearer " + jwt)
                               .header("Accept", "application/vnd.github.machine-man-preview+json")
                               .POST(HttpRequest.BodyPublishers.noBody())
                               .build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            var data = JSON.parse(response.body());
            if (!data.contains("token")) {
                throw new Token.GeneratorError("Unknown data returned: " + data);
            }
            return data.get("token").asString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            throw new Token.GeneratorError(e.toString());
        }
    }

    public String getInstallationToken() {
        return installationToken.toString();
    }

    JSONObject getAppDetails() {
        var details = URIBuilder.base(apiBase).setPath("/app").build();
        var client = HttpClient.newBuilder()
                               .connectTimeout(Duration.ofSeconds(10))
                               .build();

        try {
            var response = client.send(
                    HttpRequest.newBuilder()
                               .uri(details)
                               .timeout(Duration.ofSeconds(30))
                               .header("Authorization", "Bearer " + jwt)
                               .header("Accept", "application/vnd.github.machine-man-preview+json")
                               .GET()
                               .build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            var data = JSON.parse(response.body());
            return data.asObject();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    String authId() {
        return id;
    }
}
