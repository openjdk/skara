package org.openjdk.skara.bots.common;

import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Enum for Skara command names
 */
public enum CommandNameEnum {
    help,
    integrate,
    sponsor,
    contributor,
    summary,
    issue,
    solves,
    reviewers,
    csr,
    jep,
    reviewer,
    label,
    cc,
    clean,
    open,
    backport,
    tag;

    /* Utility method for returning command names separated by provided deliminator */
    public static String commandNamesSepByDelim(String deliminator) {
        return Stream.of(CommandNameEnum.values()).map(CommandNameEnum::name).collect(Collectors.joining(deliminator));
    }
}
