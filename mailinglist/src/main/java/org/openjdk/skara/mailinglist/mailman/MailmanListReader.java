/*
 * Copyright (c) 2019, 2022, Oracle and/or its affiliates. All rights reserved.
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

import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.zip.GZIPInputStream;
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
import org.openjdk.skara.network.URIBuilder;

public abstract class MailmanListReader implements MailingListReader {
    private final boolean useEtag;
    protected final List<String> names = new ArrayList<>();
    private final Logger log = Logger.getLogger("org.openjdk.skara.mailinglist");
    protected final ConcurrentMap<URI, HttpResponse<byte[]>> pageCache = new ConcurrentHashMap<>();
    protected List<Conversation> cachedConversations = new ArrayList<>();
    private static final HttpClient client = HttpClient.newBuilder()
                                                       .connectTimeout(Duration.ofSeconds(10))
                                                       .build();

    private static final Counter.WithOneLabel POLLING_COUNTER =
            Counter.name("skara_mailman_polling").labels("code").register();

    MailmanListReader(Collection<String> names, boolean useEtag) {
        this.useEtag = useEtag;
        for (var name : names) {
            if (name.contains("@")) {
                this.names.add(EmailAddress.parse(name).localPart());
            } else {
                this.names.add(name);
            }
        }
    }

    protected Optional<HttpResponse<byte[]>> getPage(URI uri) {
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
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            POLLING_COUNTER.labels(String.valueOf(response.statusCode())).inc();
            if (response.statusCode() == 200) {
                pageCache.put(uri, response);
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

    protected void updateCachedConversations(ArrayList<Email> emails, Duration maxAge) {
        var conversations = Mbox.parseMbox(emails);
        var threshold = ZonedDateTime.now().minus(maxAge);
        cachedConversations = conversations.stream()
                .filter(mail -> mail.first().date().isAfter(threshold))
                .collect(Collectors.toList());
    }
}

class Mailman2ListReader extends MailmanListReader {
    private final Mailman2Server server;

    Mailman2ListReader(Mailman2Server server, Collection<String> names, boolean useEtag) {
        super(names, useEtag);
        this.server = server;
    }

    @Override
    public String toString() {
        return "Mailman2List:" + String.join(", ", names);
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

    @Override
    public List<Conversation> conversations(Duration maxAge) {
        // Order pages by most recent first
        var potentialPages = getMonthRange(maxAge).stream()
                                                  .sorted(Comparator.reverseOrder())
                                                  .toList();

        var monthCount = 0;
        var newContent = false;
        var emails = new ArrayList<Email>();
        for (var month : potentialPages) {
            for (var name : names) {
                URI mboxUri = server.getMboxUri(name, month);
                var sender = EmailAddress.from(name + "@" + mboxUri.getHost());

                // For archives older than the previous month, always use cached results
                if (monthCount > 1 && pageCache.containsKey(mboxUri)) {
                    var cachedResponse = pageCache.get(mboxUri);
                    if (cachedResponse != null && cachedResponse.statusCode() != 404) {
                        emails.addAll(0, Mbox.splitMbox(new String(cachedResponse.body(), StandardCharsets.UTF_8), sender));
                    }
                } else {
                    var mboxResponse = getPage(mboxUri);
                    if (mboxResponse.isPresent()) {
                        if (mboxResponse.get().statusCode() == 304) {
                            emails.addAll(0, Mbox.splitMbox(new String(pageCache.get(mboxUri).body(), StandardCharsets.UTF_8), sender));
                        } else {
                            emails.addAll(0, Mbox.splitMbox(new String(mboxResponse.get().body(), StandardCharsets.UTF_8), sender));
                            newContent = true;
                        }
                    }
                }
            }
            monthCount++;
        }

        if (newContent) {
            updateCachedConversations(emails, maxAge);
        }

        return cachedConversations;
    }
}

class Mailman3ListReader extends MailmanListReader {
    private final Mailman3Server server;
    private final ZonedDateTime startTime;

    Mailman3ListReader(Mailman3Server server, Collection<String> names, ZonedDateTime startTime) {
        // Mailman3 does not support etag for mbox API
        super(names, false);
        this.server = server;
        this.startTime = startTime;
    }

    /**
     * Reads all emails newer than maxAge. Reads everything older than start
     * time in one go and caches that result. This chunk will always read start
     * time minus max age. Emails older than now minus max age are filtered out
     * later. Chunks newer than start time are read one day at a time, each day
     * getting cached when the next day starts. This means only the current day
     * is refreshed each time this method is called.
     *
     * @param maxAge Maximum age of emails to read relative to the start time.
     * @return Emails sorted in conversations
     */
    @Override
    public List<Conversation> conversations(Duration maxAge) {
        var now = ZonedDateTime.now();
        // First interval is everything before start time
        var start = startTime.minus(maxAge);
        var end = startTime.plusDays(1);

        var emails = new ArrayList<Email>();
        var newContent = false;
        // https://mail-dev.example.com/archives/list/skara-test@mail-dev.example.com/export/foo.mbox.gz?start=2024-10-25&end=2025-10-25

        while (start.isBefore(now)) {
            var query = Map.of("start", List.of(start.format(DateTimeFormatter.ISO_LOCAL_DATE)),
                    "end", List.of(end.format(DateTimeFormatter.ISO_LOCAL_DATE)));
            for (String name : names) {
                var mboxUri = URIBuilder.base(server.getArchiveUri()).appendPath("list/").appendPath(name)
                        .appendPath("@").appendPath(server.getArchiveUri().getHost())
                        .appendPath("/export/foo.mbox.gz").setQuery(query).build();
                var sender = EmailAddress.from(name + "@" + server.getArchiveUri().getHost());
                // For archives older than today, always use cached results
                if (end.isBefore(now) && pageCache.containsKey(mboxUri)) {
                    var cachedResponse = pageCache.get(mboxUri);
                    if (cachedResponse != null && cachedResponse.statusCode() != 404) {
                        emails.addAll(0, Mbox.splitMbox(gunzipToString(cachedResponse.body()), sender));
                    }
                } else {
                    var mboxResponse = getPage(mboxUri);
                    if (mboxResponse.isPresent()) {
                        emails.addAll(0, Mbox.splitMbox(gunzipToString(mboxResponse.get().body()), sender));
                        newContent = true;
                    }
                }
            }
            // Every interval after the first is one day
            start = end;
            end = end.plusDays(1);
        }

        if (newContent) {
            updateCachedConversations(emails, maxAge);
        }

        return cachedConversations;
    }

    private String gunzipToString(byte[] data) {
        try (var in = new InputStreamReader(new GZIPInputStream(new ByteArrayInputStream(data)), StandardCharsets.UTF_8)) {
            var out = new StringBuilderWriter();
            in.transferTo(out);
            return out.toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Simple StringWriter clone that uses a StringBuilder instead of a StringBuffer.
     */
    private static class StringBuilderWriter extends Writer {

        private final StringBuilder buf = new StringBuilder();

        @Override
        public String toString() {
            return buf.toString();
        }

        @Override
        public void write(char[] cbuf, int off, int len) {
            buf.append(cbuf, off, len);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }
}
