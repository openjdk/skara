package org.openjdk.skara.mailinglist;

import java.time.Duration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openjdk.skara.network.URIBuilder;
import org.openjdk.skara.test.EnabledIfTestProperties;
import org.openjdk.skara.test.TestProperties;

import static org.junit.jupiter.api.Assertions.assertFalse;

public class Mailman3IntegrationTests {

    private static TestProperties props;

    @BeforeAll
    static void beforeAll() {
        props = TestProperties.load();
    }

    @Test
    @EnabledIfTestProperties({"mailman3.url", "mailman3.list"})
    void testReviews() {
        var url = props.get("mailman3.url");
        var listName = props.get("mailman3.list");
        var mailmanServer = MailingListServerFactory.createMailman3Server(URIBuilder.base(url).build(), null, null);
        var listReader = mailmanServer.getListReader(listName);
        var conversations = listReader.conversations(Duration.ofDays(365));
        assertFalse(conversations.isEmpty());
    }
}
