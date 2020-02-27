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

    @Test
    void notList() {
        assertEquals("this\nis -\njust -\nnot\na\nlist", WordWrap.wrapBody("this is - just - not a list", 3));
    }

    @Test
    void complex() {
        assertEquals("> I had a\n" +
                             "> few\n" +
                             "> comments\n" +
                             "> - fix the\n" +
                             ">   spelling\n" +
                             "> - remove\n" +
                             ">   trailing\n" +
                             ">   whitespace\n" +
                             "Ok, I\n" +
                             "will fix\n" +
                             "that in a\n" +
                             "new\n" +
                             "commit!", WordWrap.wrapBody("> I had a few comments\n" +
                                                                  "> - fix the spelling\n" +
                                                                  "> - remove trailing whitespace\n" +
                                                                  "Ok, I will fix that in a new commit!",
                                                          10));
    }
}
