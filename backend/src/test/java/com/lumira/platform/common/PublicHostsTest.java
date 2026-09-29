package com.lumira.platform.common;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import org.junit.jupiter.api.Test;

class PublicHostsTest {
    @Test
    void rejectsPrivateReservedAndInternalDestinations() throws Exception {
        for (String address :
                new String[] {
                    "0.0.0.0",
                    "10.1.2.3",
                    "127.0.0.1",
                    "169.254.169.254",
                    "172.31.0.1",
                    "192.168.0.1",
                    "100.100.100.200",
                    "198.18.0.1",
                    "192.0.0.8",
                    "192.0.2.1",
                    "198.51.100.7",
                    "203.0.113.9",
                    "224.0.0.1",
                    "240.0.0.1",
                    "::1",
                    "fd12::1",
                    "fe80::1",
                    "::ffff:127.0.0.1",
                    "2002:c000:204::1",
                    "64:ff9b::a00:1",
                    "2001:db8::1"
                })
            assertFalse(PublicHosts.isPublicAddress(InetAddress.getByName(address)), address);
        assertTrue(PublicHosts.isPublicAddress(InetAddress.getByName("8.8.8.8")));
        assertTrue(PublicHosts.isPublicAddress(InetAddress.getByName("2606:4700:4700::1111")));
    }

    @Test
    void allowsOnlyPublicLookingNames() {
        for (String host :
                new String[] {
                    "localhost",
                    "a.localhost",
                    "db.internal",
                    "nas.local",
                    "x.lan",
                    "x.home",
                    "single",
                    "10.0.0.1",
                    "[::1]",
                    "",
                    null,
                    "bad_name.example.com"
                }) assertFalse(PublicHosts.isAllowed(host), String.valueOf(host));
        assertTrue(PublicHosts.isAllowed("api.example.com"));
        assertTrue(PublicHosts.isAllowed("8.8.8.8"));
    }
}
