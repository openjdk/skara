package org.openjdk.skara.bot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class LogContextTests {

    @Test
    public void simple() {
        String key = "keyname";
        assertNull(LogContextMap.get(key), "Key " + key + " already present in context");
        try (var __ = new LogContext(key, "value")) {
            assertEquals("value", LogContextMap.get(key), "Context property not set");
        }
        assertNull(LogContextMap.get(key), "Context property not removed");
    }
}
