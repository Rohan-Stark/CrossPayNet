package com.crosspaynet.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DataMaskerTest {

    @Test
    void shouldMaskAccountNumbersPreservingLastFourDigits() {
        assertThat(DataMasker.maskAccountNumber("1234567890")).isEqualTo("****7890");
        assertThat(DataMasker.maskAccountNumber("ACC-987654321")).isEqualTo("****4321");
    }

    @Test
    void shouldMaskShortAccountNumbersSafely() {
        assertThat(DataMasker.maskAccountNumber("1234")).isEqualTo("****");
        assertThat(DataMasker.maskAccountNumber("12")).isEqualTo("****");
        assertThat(DataMasker.maskAccountNumber("")).isEqualTo("****");
        assertThat(DataMasker.maskAccountNumber(null)).isEqualTo("****");
    }

    @Test
    void shouldMaskPersonalNamesPreservingOuterCharacters() {
        assertThat(DataMasker.maskName("John Doe")).isEqualTo("J***e");
        assertThat(DataMasker.maskName("Alice")).isEqualTo("A***e");
        assertThat(DataMasker.maskName("Bo")).isEqualTo("B***");
        assertThat(DataMasker.maskName("")).isEqualTo("***");
        assertThat(DataMasker.maskName(null)).isEqualTo("***");
    }
}
