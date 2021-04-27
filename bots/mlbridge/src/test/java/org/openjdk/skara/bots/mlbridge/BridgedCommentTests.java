package org.openjdk.skara.bots.mlbridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class BridgedCommentTests {

    @Test
    public void bridgeMailPattern() {
        assertFalse(BridgedComment.bridgedMailId.matcher("foo").find());
        assertFalse(BridgedComment.bridgedMailId.matcher("<-- foo -->").find());
        assertTrue(BridgedComment.bridgedMailId.matcher("<!-- Bridged id (foo=) -->").find());
        assertTrue(BridgedComment.bridgedMailId.matcher("<!-- Bridged id (PEEzNDJBNUQwLTM" +
                "4MjItNEM2Ni05MDc4LUY5QThDOTA2NTRBNkBjYmZpZGRsZS5jb20+RnJvbSB0aGUgQ1NSIHJldmlldzo=) -->").find());

        var matcher = BridgedComment.bridgedMailId.matcher("<!-- Bridged id (fo+/o=) -->");
        assertTrue(matcher.find());
        assertEquals("fo+/o=", matcher.group(1));
    }
}
