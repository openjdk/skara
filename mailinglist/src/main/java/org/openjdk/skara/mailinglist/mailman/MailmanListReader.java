/*
 * Copyright (c) 2019, 2023, Oracle and/or its affiliates. All rights reserved.
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
import org.openjdk.skara.metrics.Counter;

public class MailmanListReader implements MailingListReader {
    private final MailmanServer server;
    private final boolean useEtag;
    private final List<String> names = new ArrayList<>();
    private final Logger log = Logger.getLogger("org.openjdk.skara.mailinglist");

    // Store the response pages of the last two months, the last third month sometimes, and the 404 response.
    private final ConcurrentMap<URI, HttpResponse<String>> pageCache = new ConcurrentHashMap<>();

    // Store the email lists of most months, excluding the months mentioned in field `pageCache`.
    private final ConcurrentMap<URI, List<Email>> emailCache = new ConcurrentHashMap<>();

    private List<Conversation> cachedConversations = new ArrayList<>();
    private static final HttpClient client = HttpClient.newBuilder()
                                                       .connectTimeout(Duration.ofSeconds(10))
                                                       .build();

    private static final Counter.WithOneLabel POLLING_COUNTER =
            Counter.name("skara_mailman_polling").labels("code").register();

    MailmanListReader(MailmanServer server, Collection<String> names, boolean useEtag) {
        this.server = server;
        this.useEtag = useEtag;
        for (var name : names) {
            if (name.contains("@")) {
                this.names.add(EmailAddress.parse(name).localPart());
            } else {
                this.names.add(name);
            }
        }
    }

    @Override
    public String toString() {
        return "MailmanList:" + String.join(", ", names);
    }

    private List<ZonedDateTime> getMonthRange(Duration maxAge) {
        var now = ZonedDateTime.now();
        var start = now.minus(maxAge);
        List<ZonedDateTime> ret = new ArrayList<>();

        // Iterate all the way until start is equal to now
        while (!start.isAfter(now)) {
            ret.add(start);
            var next = start.plus(Duration.ofDays(1));
            while (start.getMonthValue() == next.getMonthValue()) {
                next = next.plus(Duration.ofDays(1));
            }
            start = next;
        }
        return ret;
    }

    private Optional<HttpResponse<String>> getPage(URI uri, EmailAddress sender, boolean isLastTwoMonth) {
        var requestBuilder = HttpRequest.newBuilder(uri)
                                        .timeout(Duration.ofSeconds(30))
                                        .GET();

        var cached = pageCache.get(uri);
        if (useEtag && cached != null) {
            var etag = cached.headers().firstValue("ETag");
            etag.ifPresent(s -> requestBuilder.header("If-None-Match", s));
        }

        var request = requestBuilder.build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            POLLING_COUNTER.labels(String.valueOf(response.statusCode())).inc();
            if (response.statusCode() == 200) {
                if (isLastTwoMonth) {
                    pageCache.put(uri, response);
                } else {
                    emailCache.put(uri, Mbox.splitMbox(response.body(), sender));
                }
                return Optional.of(response);
            } else if (response.statusCode() == 304) {
                return Optional.of(response);
            } else if (response.statusCode() == 404) {
                pageCache.put(uri, response);
                log.fine("Page not found for " + uri);
                return Optional.empty();
            } else {
                throw new RuntimeException("Bad response received: " + response);
            }
        } catch (IOException e) {
            POLLING_COUNTER.labels(e.getMessage()).inc();
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public List<Conversation> conversations(Duration maxAge) {
        // The pages are ordered by oldest first
        var potentialPages = getMonthRange(maxAge);

        var monthCount = 0;
        var newContent = false;
        var penultimateMonth = potentialPages.size() - 2;
        var emails = new ArrayList<Email>();
        for (var month : potentialPages) {
            for (var name : names) {
                URI mboxUri = server.getMbox(name, month);
                var sender = EmailAddress.from(name + "@" + mboxUri.getHost());

                // For archives older than the previous month, always use cached results
                if (monthCount < penultimateMonth && emailCache.containsKey(mboxUri)) {
                    emails.addAll(emailCache.get(mboxUri));
                } else if (monthCount < penultimateMonth && !emailCache.containsKey(mboxUri) && pageCache.containsKey(mboxUri)) {
                    var cachedResponse = pageCache.get(mboxUri);
                    if (cachedResponse.statusCode() == 404) {
                        continue;
                    }
                    // The **new** third month from last when entering a new month.
                    var thirdMonthResponse = pageCache.remove(mboxUri);
                    var thirdMonthEmails = Mbox.splitMbox(thirdMonthResponse.body(), sender);
                    emailCache.put(mboxUri, thirdMonthEmails);
                    emails.addAll(thirdMonthEmails);
                } else if (monthCount >= penultimateMonth) {
                    // The last two months always get the newest data.
                    var mboxResponse = getPage(mboxUri, sender, true);
                    if (mboxResponse.isPresent()) {
                        if (mboxResponse.get().statusCode() == 304) {
                            emails.addAll(Mbox.splitMbox(pageCache.get(mboxUri).body(), sender));
                        } else {
                            emails.addAll(Mbox.splitMbox(mboxResponse.get().body(), sender));
                            newContent = true;
                        }
                    }
                } else {
                    // Not the last two months and the bot is just (re)started.
                    var mboxResponse = getPage(mboxUri, sender, false);
                    if (mboxResponse.isPresent()) {
                        if (mboxResponse.get().statusCode() == 404) {
                            continue;
                        }
                        emails.addAll(emailCache.get(mboxUri));
                        newContent = true;
                    }
                }
            }
            monthCount++;
        }

        if (newContent) {
            var conversations = Mbox.parseMbox(emails);
            var threshold = ZonedDateTime.now().minus(maxAge);
            cachedConversations = conversations.stream()
                                       .filter(mail -> mail.first().date().isAfter(threshold))
                                       .collect(Collectors.toList());
        }

        return cachedConversations;
    }
}
