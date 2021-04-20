package org.openjdk.skara.bot;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * A LogContext is used to temporarily add extra log metadata in the current thread.
 * It should be initiated with a try-with-resources construct. The variable itself
 * is never used, we only want the controlled automatic close at the end of the try
 * block. Typically name the variable __. Example:
 *
 * try (var __ = new LogContext("foo", "bar")) {
 *     // some code that logs stuff
 * }
 */
public class LogContext implements AutoCloseable {
    private static final Logger log = Logger.getLogger("org.openjdk.skara.bot");
    private final Map<String, String> context = new HashMap<>();

    public LogContext(String key, String value) {
        this.init(Map.of(key, value));
    }

    public LogContext(Map<String, String> ctx) {
        this.init(ctx);
    }

    private void init(Map<String, String> newContext) {
        for (var entry : newContext.entrySet()) {
            String currentValue = LogContextMap.get(entry.getKey());
            if (currentValue != null) {
                if (!currentValue.equals(entry.getValue())) {
                    log.severe("Tried to override the current LogContext value: " + currentValue
                            + " for " + entry.getKey() + " with a different value: " + entry.getValue());
                }
            } else {
                this.context.put(entry.getKey(), entry.getValue());
                LogContextMap.put(entry.getKey(), entry.getValue());
            }
        }

    }

    public void close() {
        this.context.forEach((key, value) -> {
            LogContextMap.remove(key);
        });
    }
}
