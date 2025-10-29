package org.openjdk.skara.mailinglist.mailman;

import java.time.Duration;
import org.openjdk.skara.mailinglist.MailingListReader;

/**
 * MailingListServer implementation that only implements the send message API.
 */
public class SendOnlyServer extends MailmanServer {
    public SendOnlyServer(String smtpServer, Duration sendInterval) {
        super(null, smtpServer, sendInterval, false);
    }

    @Override
    public MailingListReader getListReader(String... listNames) {
        throw new UnsupportedOperationException();
    }
}
