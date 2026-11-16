package com.hospio.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** DEF-070 (Sam, 15 Nov): a pay-out reason or till note has to say something. */
class WordsTest {

    @Test
    void realWordsPassAndJunkIsRefused() {
        assertThat(Words.meaningful("Diesel 20L")).isTrue();
        assertThat(Words.meaningful("Taxi for guest in A204")).isTrue();
        assertThat(Words.meaningful("1234567-0-=-")).isFalse();
        assertThat(Words.meaningful("x")).isFalse();
        assertThat(Words.meaningful("   ")).isFalse();
        assertThat(Words.meaningful(null)).isFalse();
    }
}
