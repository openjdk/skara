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
package org.openjdk.skara.host.github;

import org.openjdk.skara.network.URIBuilder;
import org.openjdk.skara.test.TemporaryDirectory;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class GitHubHostTests {
    private void generateKeyfile(Path path) throws IOException {
        // This key was randomly generated for this test only
        Files.writeString(path, "-----BEGIN PRIVATE KEY-----\n" +
                "MIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQDAFzH+URXAvOoL\n" +
                "0NSdIePQTTVsan13c+7D9tAilJAtRcxUjOz2lMZYBzrdsVYGbktfseEvF6o9dyoX\n" +
                "X/py6DM0QqBNW/0uEv1ouS44po0VvykHVXrAq0u8E8HHFtr09VQSO/ceXrFd6haQ\n" +
                "aCckbdp1TPn1q8w+U2bRkqUji7zzfwll6AaB4dhKZ1v5NFuff1PWmuk2x7b0u2yR\n" +
                "uANLHLqmNB4ik7bUTiIyacXeVSZRZRFGwJjd+1WnyiybwV6QbQ0nndw6iaz2wGWt\n" +
                "XDif7DJE0axMReUZVKJLqMagS5R5ra6FdlnUPw0nbJMwnDOLk9ofSfne0LTSTD6K\n" +
                "/VZ26izbAgMBAAECggEBALF0vDq1reLgo1dHFSQUquFEcpY1yrMP5wQifyVzGb65\n" +
                "PIrfpgomZxXrl/Y2XcKTIg7FxcI7moouDDSL9lMxMByXcIAG+14VLQYSDSFIvA3b\n" +
                "C4w666wSk2Ss29eQxbaG7aPqweDMmg6osy+1CHQfCDJVapYKoCTz54i0cNrlvSk0\n" +
                "FZ3o99uAvAcLtrsqbnXO57NXQVajoSH0bkMZd+TuZqEIX3CzHoNEVhzvqaKedqA6\n" +
                "Cd22Y2m6cnW0H10Chko05FtskLYD+jw275LiUtInplBtG3n5/uDIamsOPo9XG8i0\n" +
                "a4rxaJYsRqXYqDOEjLi/QCUrYBtJ+gqT/qMOTjAoKAECgYEA/VPdvc03vScjIu4T\n" +
                "vNXjXxv81HZPm/IoTYTgvTvrEqErQ/CIwTQJer1XUJ9M43n+XkVZsMKkUIMlwt2+\n" +
                "G0wBwYkDUgIXFEJhb170BVgwyZHE+Djr0E7NunbAv/oQu8AfQzk5HZpcQwxVg8w8\n" +
                "Vj2ecLb4GK0D9iJ4zLwlsRw2RukCgYEAwh30AG7gq5y9Mj/BusuDvyNZZKjE/pJz\n" +
                "HtC7a/OzOyr+Bpr2VBxVDeEFth22bd/a4ohv1QcwNAa2LzelNfQRQURq/vqpDmuj\n" +
                "g0ESQavh3i3Tax2LXO582HWueuNL+8Ufyb6WDJDvYuz0F3WBJhxixP3I7VgMhPWV\n" +
                "tK/wEEDDwyMCgYEArR3M4NIHDzpZppsv3dIE6ZAEvWSEjrtzk1YFBwyVXkvJd0o/\n" +
                "Clj3SWtu6eeS8bkCfYXC/ypkg6i7+2jxa1ILuShanoZTI0Mhtqwa8jQMUxNMmZy8\n" +
                "ecQAjzZsDkVjfgqS0quePn6oIiGhpsnBSeYeCkTfUm2Z0XBJQRAqadgvt1ECgYBK\n" +
                "FAgzyhxvIUeKT45s7JGAdcr9gPJ8fAL2tY1wqvWxFL0QZD6w5ocG3uLBFyGxWIY9\n" +
                "gPe8ghvBHvaTmlav+k5DbAqw95Ngb29c/Y4sBZ4SncZa0FGIy3JVYMOPHgK3OAjj\n" +
                "gpncfcr9I5QbB7qbgqWmq3rsKHfOnbHd3G5upWiPpQKBgCaPW2vyT/nfCvfh0z//\n" +
                "QSv0//4zy7pDdOolP5ZRsUo5cU4aiv4XgTSglR2jEJyr4bMYCN/+4tnqp0tIUzt1\n" +
                "RWJhXLU1dm4QhCTccWMAyQgktn3SB5Ww3+qyLr1klUwkO+rx8kkNjv3rC/u5EzQ9\n" +
                "q3DJ9in4wyYBNPVDB5kJom5i\n" +
                "-----END PRIVATE KEY-----", StandardCharsets.UTF_8);
    }

    @Test
    void webUriPatternReplacement() throws IOException, URISyntaxException {
        try (var tempFolder = new TemporaryDirectory()) {
            var key = tempFolder.path().resolve("key.pem");
            generateKeyfile(key);
            var app = new GitHubApplication(key.toString(), "y", "z");
            var host = new GitHubHost(URIBuilder.base("http://www.example.com").build(),
                                      app, Pattern.compile("^(http://www.example.com)/test/(.*)$"), "$1/another/$2");
            assertEquals(new URI("http://www.example.com/another/hello"), host.getWebURI("/test/hello"));
        }
    }
}
