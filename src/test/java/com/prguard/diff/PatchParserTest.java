package com.prguard.diff;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PatchParserTest {

    @Test
    void parse_mixedHunk_tracksOldAndNewLineNumbers() {
        String patch = """
                @@ -10,4 +10,5 @@ public class A {
                     int a;
                -    int b;
                +    int b2;
                +    int c;
                     int d;
                 }""";

        Patch parsed = PatchParser.parse(patch);

        assertThat(parsed.hunks()).hasSize(1);
        assertThat(parsed.addedLines()).containsExactly(11, 12);
        assertThat(parsed.removedLines()).containsExactly(11);
        assertThat(parsed.commentableLines()).containsExactly(10, 11, 12, 13, 14);
    }

    @Test
    void parse_twoHunksAndNoNewlineMarker_parsesBoth() {
        String patch = """
                @@ -1,2 +1,2 @@
                -a
                +b
                 c
                @@ -20 +20,2 @@
                 x
                +y
                \\ No newline at end of file""";

        Patch parsed = PatchParser.parse(patch);

        assertThat(parsed.hunks()).hasSize(2);
        assertThat(parsed.addedLines()).containsExactly(1, 21);
        assertThat(parsed.removedLines()).containsExactly(1);
    }

    @Test
    void parse_nullPatch_returnsEmpty() {
        assertThat(PatchParser.parse(null).hunks()).isEmpty();
    }
}
