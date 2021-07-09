package org.openjdk.skara.test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.Properties;

/**
 * This class provides settings for manual tests which the user provides
 * through the manual-test-settings.properties file in the root of the project.
 */
public class ManualTestSettings {

    public static final String MANUAL_TEST_SETTINGS_FILE = "manual-test-settings.properties";

    public static Properties loadManualTestSettings() throws IOException {
        var dir = Paths.get(".").toAbsolutePath();
        Path file = dir.resolve(MANUAL_TEST_SETTINGS_FILE);
        while (!Files.exists(file)) {
            dir = dir.getParent();
            if (!Files.isDirectory(dir)) {
                throw new RuntimeException("Could not find " + MANUAL_TEST_SETTINGS_FILE);
            }
            file = dir.resolve(MANUAL_TEST_SETTINGS_FILE);
        }
        var properties = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            properties.load(in);
        }
        return properties;
    }
}
