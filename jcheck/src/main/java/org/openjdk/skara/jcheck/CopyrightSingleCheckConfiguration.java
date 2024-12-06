package org.openjdk.skara.jcheck;

import java.util.regex.Pattern;

public class CopyrightSingleCheckConfiguration {
    String name;
    Pattern locator;
    Pattern checker;
    boolean required;

    CopyrightSingleCheckConfiguration(String name, Pattern locator, Pattern checker, boolean required) {
        this.name = name;
        this.locator = locator;
        this.checker = checker;
        this.required = required;
    }
}
