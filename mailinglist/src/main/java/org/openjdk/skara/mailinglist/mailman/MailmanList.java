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
package org.openjdk.skara.mailinglist.mailman;

import org.openjdk.skara.email.*;
import org.openjdk.skara.mailinglist.*;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class MailmanList implements MailingList {
    private final MailmanServer server;
    private final EmailAddress listAddress;
    private final Logger log = Logger.getLogger("org.openjdk.skara.mailinglist");
    private final ConcurrentMap<URI, HttpResponse<String>> pageCache = new ConcurrentHashMap<>();
    private List<Conversation> cachedConversations = new ArrayList<>();
    private static final HttpClient client = HttpClient.newBuilder()
                                                       .connectTimeout(Duration.ofSeconds(10))
                                                       .build();

    MailmanList(MailmanServer server, EmailAddress name) {
        this.server = server;
        this.listAddress = name;
    }

    @Override
    public String toString() {
        return "MailmanList:" + listAddress;
    }

    @Override
    public void post(Email email) {
        server.sendMessage(listAddress, email);
    }

    private List<ZonedDateTime> getMonthRange(Duration maxAge) {
        var now = ZonedDateTime.now();
        var start = now.minus(maxAge);
        List<ZonedDateTime> ret = new ArrayList<>();

        while (start.isBefore(now)) {
            ret.add(start);
            var next = start.plus(Duration.ofDays(1));
            while (start.getMonthValue() == next.getMonthValue()) {
                next = next.plus(Duration.ofDays(1));
            }
            start = next;
        }
        return ret;
    }

    private Optional<HttpResponse<String>> getPage(HttpClient client, URI uri) {
        var requestBuilder = HttpRequest.newBuilder(uri)
                                        .timeout(Duration.ofSeconds(30))
                                        .GET();

        var cached = pageCache.get(uri);
        if (cached != null) {
            var etag = cached.headers().firstValue("ETag");
            etag.ifPresent(s -> requestBuilder.header("If-None-Match", s));
        }

        var request = requestBuilder.build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                pageCache.put(uri, response);
                return Optional.of(response);
            } else if (response.statusCode() == 304) {
                return Optional.of(response);
            } else if (response.statusCode() == 404) {
                log.fine("Page not found for " + uri);
                return Optional.empty();
            } else {
                throw new RuntimeException("Bad response received: " + response);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public List<Conversation> conversations(Duration maxAge) {
        // Order pages by most recent first
        var potentialPages = getMonthRange(maxAge).stream()
                                                  .sorted(Comparator.reverseOrder())
                                                  .collect(Collectors.toList());

        var actualPages = new LinkedList<String>();
        var useCached = false;
        var newContent = false;
        for (var month : potentialPages) {
            URI mboxUri = server.getMbox(listAddress.localPart(), month);

            if (useCached) {
                var cachedResponse = pageCache.get(mboxUri);
                if (cachedResponse == null) {
                    break;
                } else {
                    actualPages.addFirst(cachedResponse.body());
                }
            } else {
                var mboxResponse = getPage(client, mboxUri);
                if (mboxResponse.isEmpty()) {
                    break;
                }
                if (mboxResponse.get().statusCode() == 304) {
                    actualPages.addFirst(pageCache.get(mboxUri).body());
                    useCached = true;
                } else {
                    actualPages.addFirst(mboxResponse.get().body());
                    newContent = true;
                }
            }
        }

        if (newContent) {
            var concatenatedMbox = String.join("", actualPages);
            var mails = Mbox.parseMbox(concatenatedMbox, listAddress);
            var threshold = ZonedDateTime.now().minus(maxAge);
            cachedConversations = mails.stream()
                                       .filter(mail -> mail.first().date().isAfter(threshold))
                                       .collect(Collectors.toList());
        }

        return cachedConversations;
    }
}
