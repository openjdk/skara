package org.openjdk.skara.bots.common;

import java.util.regex.Pattern;

import static java.util.regex.Pattern.DOTALL;
import static java.util.regex.Pattern.MULTILINE;
import static java.util.regex.Pattern.compile;

/**
 * Enum for commonly used Regex patterns
 */
public enum PatternEnum {

    EXECUTION_COMMAND_PATTERN(compile("^\\s*/([a-z]+)(?:\\s+|$)(.*)?")),
    ARCHIVAL_COMMAND_PATTERN(compile(EXECUTION_COMMAND_PATTERN.pattern.pattern(), Pattern.MULTILINE | Pattern.DOTALL)),
    COMMENT_PATTERN(compile("<!--.*?-->", DOTALL | MULTILINE));

    private final Pattern pattern;

    PatternEnum(Pattern pattern) {
        this.pattern = pattern;
    }

    public Pattern getPattern() {
        return this.pattern;
    }
}
