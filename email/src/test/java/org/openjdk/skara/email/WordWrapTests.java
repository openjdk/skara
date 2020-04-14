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

    @Test
    void emptyLines() {
        assertEquals("hello\nthere\n\nyou", WordWrap.wrapBody("hello there\n\nyou", 3));
    }

    @Test
    void complexList() {
        assertEquals("Problems:\n" +
                             "- G1 pre- and post-barriers used when (un-)packing arguments for the calling convention can call into the runtime which\n" +
                             "  screws up argument registers. Save all registers until JDK-8232094 is fixed in mainline (it's the slow path anyway).\n" +
                             "- SignatureStream::as_value_klass triggers a SystemDictionary lookup which acquires the ProtectionDomainSet_lock. When\n" +
                             "  used from fieldDescriptor::print_on_for when some debug printing flags are enabled, this conflicts with the tty_lock.\n" +
                             "  We should simply use get_value_field_klass instead. Also, we should handle null as a vale for non-flattened fields.\n" +
                             "- TraceDeoptimization needs to handle re-allocation of the inline type return value.\n" +
                             "\n" +
                             "I've also added a new StressCC option to the ValueTypeTest suite to randomly restrict some compilation to C1 and\n" +
                             "thereby stress test the calling convention.",
                     WordWrap.wrapBody("Problems:\n" +
                                               "- G1 pre- and post-barriers used when (un-)packing arguments for the calling convention can call into the runtime which screws up argument registers. Save all registers until JDK-8232094 is fixed in mainline (it's the slow path anyway).\n" +
                                               "- SignatureStream::as_value_klass triggers a SystemDictionary lookup which acquires the ProtectionDomainSet_lock. When used from fieldDescriptor::print_on_for when some debug printing flags are enabled, this conflicts with the tty_lock. We should simply use get_value_field_klass instead. Also, we should handle null as a vale for non-flattened fields.\n" +
                                               "- TraceDeoptimization needs to handle re-allocation of the inline type return value.\n" +
                                               "\n" +
                                               "I've also added a new StressCC option to the ValueTypeTest suite to randomly restrict some compilation to C1 and thereby stress test the calling convention.", 120));
    }
}
