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
package org.openjdk.skara.email;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class Email {
    private final EmailAddress id;
    private final ZonedDateTime date;
    private final List<EmailAddress> recipients;
    private final EmailAddress author;
    private final EmailAddress sender;
    private final String subject;
    private final String body;
    private final Map<String, String> headers;

    private final static Pattern mboxMessageHeaderBodyPattern = Pattern.compile(
            "(\\r\\n){2}|(\\n){2}", Pattern.MULTILINE);
    private final static Pattern mboxMessageHeaderPattern = Pattern.compile(
            "^([-\\w]+):\\R? ((?:.(?!\\R\\w))*.)", Pattern.MULTILINE | Pattern.DOTALL);
    private final static Pattern mimeHeadersPattern = Pattern.compile(
            "^(Content-Type|Content-Transfer-Encoding): .*");
    private final static Pattern charsetPattern = Pattern.compile("charset=\"([a-zA-Z0-9-]+)\"");

    Email(EmailAddress id, ZonedDateTime date, List<EmailAddress> recipients, EmailAddress author, EmailAddress sender, String subject, String body, Map<String, String> headers) {
        this.id = id;
        this.date = date.truncatedTo(ChronoUnit.SECONDS);
        this.recipients = new ArrayList<>(recipients);
        this.sender = sender;
        this.subject = subject;
        this.body = body;
        this.author = author;
        this.headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        this.headers.putAll(headers);
    }

    private static class MboxMessage {
        Map<String, String> headers;
        String body;
    }

    private static MboxMessage parseMboxMessage(String message) {
        var ret = new MboxMessage();

        var parts = mboxMessageHeaderBodyPattern.split(message, 2);
        var headers = mboxMessageHeaderPattern.matcher(parts[0]).results()
                                              .collect(Collectors.toMap(match -> match.group(1),
                                                                        match -> match.group(2)
                                                                                      .replaceAll("\\R", "")));
        ret.headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        ret.headers.putAll(headers);

        var boundary = extractContentBoundary(ret.headers);
        if (boundary != null) {
            var body = new StringBuilder();
            var bodySections = parts[1].split("\\R?--" + boundary + "(?:--)?\\R");
            for (String bodySection : bodySections) {
                if (bodySection.lines().findFirst().map(e -> mimeHeadersPattern.matcher(e).matches()).orElse(false)) {
                    var mimeHeaders = bodySection.lines()
                            .takeWhile(s -> !s.isEmpty())
                            .map(mboxMessageHeaderPattern::matcher)
                            .filter(Matcher::matches)
                            .collect(Collectors.toMap(match -> match.group(1), match -> match.group(2)));
                    // Skip any non plain text part
                    if (mimeHeaders.containsKey("Content-Type") && !mimeHeaders.get("Content-Type").startsWith("text/plain")) {
                        continue;
                    }
                    // Remove the mime headers from the rest of the body section
                    var bodySectionBody = bodySection.split("\\R{2}", 2)[1];
                    // Mailman3 encodes mail bodies with "quoted-printable".
                    if ("quoted-printable".equals(mimeHeaders.get("Content-Transfer-Encoding"))) {
                        Matcher encodingMatcher = charsetPattern.matcher(mimeHeaders.get("Content-Type"));
                        String charsetName;
                        if (encodingMatcher.find()) {
                            charsetName = encodingMatcher.group(1);
                        } else {
                            charsetName = "utf-8";
                        }
                        bodySectionBody = decodeQuotedPrintable(bodySectionBody, charsetName);
                    }
                    body.append(bodySectionBody.stripTrailing());
                } else {
                    body.append(bodySection.stripTrailing());
                }
            }
            ret.body = body.toString();
        } else {
            ret.body = parts[1].stripTrailing();
        }
        return ret;
    }

    /**
     * Decode quoted printable encoding text. Non ASCII characters are encoded
     * as series of `=XX` where `XX` is the hex value of a byte. Newlines in
     * the encoding are escaped with `=`.
     * @param s The string to be decoded.
     * @param charsetName The charset name to use when converting bytes to a
     *                    back to a String.
     * @return A String with the decoded contents.
     */
    private static String decodeQuotedPrintable(String s, String charsetName) {
        byte[] in = s.getBytes(StandardCharsets.US_ASCII);
        // The decoded buffer can never be longer than the encoded buffer as
        // every decoding step reduces bytes.
        byte[] out = new byte[in.length];
        int j = 0;
        for (int i = 0; i < in.length; i++) {
            if (in[i] == '=') {
                i++;
                switch (in[i]) {
                    case '\n' : break;
                    case '\r' : {
                        if (in[i + 1] == '\n') {
                            i++;
                        }
                        break;
                    }
                    default : {
                        out[j++] = (byte) Integer.parseInt("" + (char) in[i++] + (char) in[i], 16);
                        break;
                    }
                }
            } else {
                out[j++] = in[i];
            }
        }
        try {
            return new String(out, 0, j, charsetName);
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    private final static Pattern mboxBoundaryPattern = Pattern.compile(".*boundary=\"([^\"]*)\".*");

    // Content-Type: multipart/mixed; boundary="===============3685582790409215631=="
    private static String extractContentBoundary(Map<String, String> headers) {
        if (headers.containsKey("Content-Type")) {
            var contentType = headers.get("Content-Type");
            var matcher = mboxBoundaryPattern.matcher(contentType);
            if (matcher.matches()) {
                return matcher.group(1);
            }
        }
        return null;
    }

    private static final Pattern redundantTimeZonePattern = Pattern.compile("^(.*[-+\\d{4}]) \\(\\w+\\)$");

    public static Email parse(String raw) {
        var message = parseMboxMessage(raw);

        var id = EmailAddress.parse(message.headers.get("Message-Id"));
        var unparsedDate = message.headers.get("Date");
        var redundantTimeZonePatternMatcher = redundantTimeZonePattern.matcher(unparsedDate);
        if (redundantTimeZonePatternMatcher.matches()) {
            unparsedDate = redundantTimeZonePatternMatcher.group(1);
        }
        var date = ZonedDateTime.parse(unparsedDate, DateTimeFormatter.RFC_1123_DATE_TIME);
        var subject = MimeText.decode(message.headers.get("Subject"));
        var author = EmailAddress.parse(MimeText.decode(message.headers.get("From")));
        var sender = author;
        if (message.headers.containsKey("Sender")) {
            sender = EmailAddress.parse(MimeText.decode(message.headers.get("Sender")));
        }
        List<EmailAddress> recipients;
        if (message.headers.containsKey("To")) {
            recipients = Arrays.stream(message.headers.get("To").split(","))
                               .map(MimeText::decode)
                               .map(String::strip)
                               .map(EmailAddress::parse)
                               .collect(Collectors.toList());
        } else {
            recipients = List.of();
        }

        // Remove all known headers
        var filteredHeaders = message.headers.entrySet().stream()
                                             .filter(entry -> !entry.getKey().equalsIgnoreCase("Message-Id"))
                                             .filter(entry -> !entry.getKey().equalsIgnoreCase("Date"))
                                             .filter(entry -> !entry.getKey().equalsIgnoreCase("Subject"))
                                             .filter(entry -> !entry.getKey().equalsIgnoreCase("From"))
                                             .filter(entry -> !entry.getKey().equalsIgnoreCase("Sender"))
                                             .filter(entry -> !entry.getKey().equalsIgnoreCase("To"))
                                             .filter(entry -> !entry.getKey().equalsIgnoreCase("Content-type"))
                                             .collect(Collectors.toMap(Map.Entry::getKey,
                                                                       entry -> MimeText.decode(entry.getValue())));

        return new Email(id, date, recipients, author, sender, subject, message.body, filteredHeaders);
    }

    public static EmailBuilder create(EmailAddress author, String subject, String body) {
        return new EmailBuilder(author, subject, body);
    }

    public static EmailBuilder create(String subject, String body) {
        return new EmailBuilder(subject, body);
    }

    public static EmailBuilder from(Email email) {
        return new EmailBuilder(email.author, email.subject, email.body)
                .sender(email.sender)
                .recipients(email.recipients)
                .id(email.id)
                .date(email.date)
                .headers(email.headers);
    }

    public static EmailBuilder reply(Email parent, String subject, String body) {
        var references = parent.id().toString();
        if (parent.hasHeader("References")) {
            references = parent.headerValue("References") + " " + references;
        }

        return new EmailBuilder(subject, body)
                .header("In-Reply-To", parent.id().toString())
                .header("References", references);
    }

    public static EmailBuilder reparent(Email newParent, Email email) {
        var currentParent = email.headerValue("In-Reply-To");
        var currentRefs = email.headerValue("References");

        return from(email).header("In-Reply-To", newParent.id.toString())
                          .header("References", currentRefs.replace(currentParent, newParent.id.toString()));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        Email email = (Email) o;
        return id.equals(email.id) &&
                date.toEpochSecond() == email.date.toEpochSecond() &&
                recipients.equals(email.recipients) &&
                author.equals(email.author) &&
                sender.equals(email.sender) &&
                subject.equals(email.subject) &&
                body.equals(email.body) &&
                headers.equals(email.headers);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, date.toEpochSecond(), recipients, author, sender, subject, body, headers);
    }

    public EmailAddress id() {
        return id;
    }

    public List<EmailAddress> recipients() {
        return new ArrayList<>(recipients);
    }

    public EmailAddress author() {
        return author;
    }

    public EmailAddress sender() {
        return sender;
    }

    public ZonedDateTime date() {
        return date;
    }

    public String subject() {
        return subject;
    }

    public String body() {
        return body;
    }

    public Set<String> headers() {
        return new HashSet<>(headers.keySet());
    }

    public boolean hasHeader(String header) {
        return headers.containsKey(header);
    }

    public String headerValue(String header) {
        return headers.get(header);
    }

    @Override
    public String toString() {
        return "Email{" +
                "id='" + id + '\'' +
                ", date=" + date +
                ", recipients=" + recipients +
                ", author=" + author +
                ", sender=" + sender +
                ", subject='" + subject + '\'' +
                ", body='" + body + '\'' +
                ", headers=" + headers +
                '}';
    }
}
