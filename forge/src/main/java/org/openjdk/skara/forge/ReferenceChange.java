package org.openjdk.skara.forge;

import java.time.ZonedDateTime;

public record ReferenceChange(String from, String to, ZonedDateTime at) {
}
