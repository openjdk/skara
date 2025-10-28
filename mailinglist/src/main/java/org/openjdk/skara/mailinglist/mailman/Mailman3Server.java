package org.openjdk.skara.mailinglist.mailman;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import org.openjdk.skara.mailinglist.MailingListReader;

public class Mailman3Server extends MailmanServer {

    public Mailman3Server(URI archive, String smtpServer, Duration sendInterval, boolean useEtag) {
        super(archive, smtpServer, sendInterval, useEtag);
    }

    URI getArchiveUri() {
        return archive;
    }

    @Override
    public MailingListReader getListReader(String... listNames) {
        return new Mailman3ListReader(this, Arrays.asList(listNames), useEtag);
    }
}
