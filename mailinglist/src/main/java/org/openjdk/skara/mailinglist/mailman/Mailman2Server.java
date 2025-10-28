package org.openjdk.skara.mailinglist.mailman;

import java.net.URI;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Locale;
import org.openjdk.skara.mailinglist.MailingListReader;
import org.openjdk.skara.network.URIBuilder;

public class Mailman2Server extends MailmanServer {

    public Mailman2Server(URI archive, String smtpServer, Duration sendInterval, boolean useEtag) {
        super(archive, smtpServer, sendInterval, useEtag);
    }

    URI getMboxUri(String listName, ZonedDateTime month) {
        var dateStr = DateTimeFormatter.ofPattern("yyyy-MMMM", Locale.US).format(month);
        return URIBuilder.base(archive).appendPath(listName + "/" + dateStr + ".txt").build();
    }

    @Override
    public MailingListReader getListReader(String... listNames) {
        return new Mailman2ListReader(this, Arrays.asList(listNames), useEtag);
    }
}
