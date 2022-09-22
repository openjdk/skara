package org.openjdk.skara.forge;

import java.time.ZonedDateTime;

public record RefChange(String prevRefName, String curRefName, ZonedDateTime createdAt) {
}
