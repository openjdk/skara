package org.openjdk.skara.bot;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * This class holds a static thread local hashmap to store temporary log
 * metadata which our custom LogStreamers can pick up and include in log
 * messages.
 */
public class LogContextMap {

    private static final ThreadLocal<HashMap<String, String>> threadContextMap = new ThreadLocal<>();

    public static void put(String key, String value) {
        if (threadContextMap.get() == null) {
            threadContextMap.set(new HashMap<>());
        }
        var map = threadContextMap.get();
        map.put(key, value);
    }

    public static String get(String key) {
        if (threadContextMap.get() != null) {
            return threadContextMap.get().get(key);
        } else {
            return null;
        }
    }

    public static String remove(String key) {
        if (threadContextMap.get() != null) {
            return threadContextMap.get().remove(key);
        } else {
            return null;
        }
    }

    public static Set<Map.Entry<String, String>> entrySet() {
        if (threadContextMap.get() != null) {
            return threadContextMap.get().entrySet();
        } else {
            return Collections.emptySet();
        }
    }
}
