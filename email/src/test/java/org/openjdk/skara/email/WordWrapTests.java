package org.openjdk.skara.email;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class WordWrapTests {
    @Test
    void simple() {
        assertEquals("hello\nthere\nyou", WordWrap.wrapBody("hello there you", 2));
        assertEquals("hello\nthere", WordWrap.wrapBody("hello there", 7));
        assertEquals("hello there", WordWrap.wrapBody("hello there", 20));
        assertEquals("hello\nthere", WordWrap.wrapBody("hello   there", 7));
    }

    @Test
    void indented() {
        assertEquals("  hello\n  there", WordWrap.wrapBody("  hello there", 10));
        assertEquals("  hello\n  there\n you", WordWrap.wrapBody("  hello there\n you", 10));
    }

    @Test
    void quoted() {
        assertEquals("> hello\n> there", WordWrap.wrapBody("> hello there", 10));
        assertEquals("> hello\n> there\n> you", WordWrap.wrapBody("> hello there\n> you", 2));
        assertEquals(">> hello\n>> there\n> you", WordWrap.wrapBody(">> hello there\n> you", 2));
    }

    @Test
    void list() {
        assertEquals(" - hello\n   there\n - you", WordWrap.wrapBody(" - hello there\n - you", 10));
        assertEquals(" - hello\n   there", WordWrap.wrapBody(" - hello there", 10));
    }
}
