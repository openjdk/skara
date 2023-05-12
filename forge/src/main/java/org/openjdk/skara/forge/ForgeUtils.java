package org.openjdk.skara.forge;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

public class ForgeUtils {

    /**
     * Adds a special ssh key configuration in the user's ssh config file.
     * The config will only apply to the fake host userName.hostName so should
     * not interfere with other user configurations. The caller of this method
     * needs to use userName.hostName as host name when calling ssh.
     */
    public static void configureSshKey(String userName, String hostName, String sshKeyFile) {
        var cfgPath = Path.of(System.getProperty("user.home"), ".ssh");
        if (!Files.isDirectory(cfgPath)) {
            try {
                Files.createDirectories(cfgPath);
            } catch (IOException ignored) {
            }
        }

        var cfgFile = cfgPath.resolve("config");
        var existing = "";
        try {
            existing = Files.readString(cfgFile, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }

        var userHost = userName + "." + hostName;
        var existingBlock = Pattern.compile("^Match host " + Pattern.quote(userHost) + "(?:\\R[ \\t]+.*)+", Pattern.MULTILINE);
        var existingMatcher = existingBlock.matcher(existing);
        var filtered = existingMatcher.replaceAll("");
        var result = "Match host " + userHost + "\n" +
                "  Hostname " + hostName + "\n" +
                "  PreferredAuthentications publickey\n" +
                "  StrictHostKeyChecking no\n" +
                "  IdentityFile " + sshKeyFile + "\n" +
                "\n";

        try {
            Files.writeString(cfgFile, result + filtered.strip() + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
