package com.hospio.messaging.mail;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Reserved test domains never receive mail (docs/email.md, "Test addresses are never sent"). */
class TestAddressesTest {

    @ParameterizedTest
    @ValueSource(strings = {"qa@hospio-qa.test", "a@b.test", "owner@hotel.example", "x@host.invalid", "x@localhost", "x@app.localhost",
            "x@printer.local", "x@example.com", "x@example.org", "x@example.net", "x@mail.example.com", "X@EXAMPLE.COM", " x@example.com ",
            "x@example.com.", "Ada <ada@guest.test>"})
    void reserved(String address) {
        assertThat(TestAddresses.reserved(address)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"tunde@royalsuites.ng", "someone@gmail.com", "ops@hospio360.com", "x@example.co.uk", "x@myexample.com",
            "x@example.com.ng", "x@test.com", "x@local.ng", "x@testing", "", "no-at-sign", "x@"})
    void notReserved(String address) {
        assertThat(TestAddresses.reserved(address)).isFalse();
    }
}
