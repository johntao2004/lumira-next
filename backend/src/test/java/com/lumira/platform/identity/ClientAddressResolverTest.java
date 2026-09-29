package com.lumira.platform.identity;
import com.lumira.platform.common.ClientAddressResolver;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.junit.jupiter.api.Assertions.*;

class ClientAddressResolverTest {
    @Test void untrustedClientsCannotForgeEitherHeader() {
        var request=new MockHttpServletRequest();request.setRemoteAddr("192.0.2.9");
        request.addHeader(ClientAddressResolver.HEADER,"203.0.113.5");request.addHeader("X-Forwarded-For","203.0.113.7");
        assertEquals("192.0.2.9",new ClientAddressResolver("192.0.2.8").resolve(request));
    }
    @Test void trustedProxySuppliesSingleLiteralAndCanonicalizesIpv6() {
        var request=new MockHttpServletRequest();request.setRemoteAddr("192.0.2.8");request.addHeader(ClientAddressResolver.HEADER,"2001:db8::1");
        var resolver=new ClientAddressResolver("192.0.2.8");
        assertEquals("2001:db8:0:0:0:0:0:1",resolver.resolve(request));
        request.removeHeader(ClientAddressResolver.HEADER);request.addHeader(ClientAddressResolver.HEADER,"203.0.113.5");
        assertEquals("203.0.113.5",resolver.resolve(request));
    }
    @Test void malformedMissingOrRepeatedHeadersFallBackToPeer() {
        var resolver=new ClientAddressResolver("192.0.2.8");
        for(var value:new String[]{"localhost","192.0.2.1, 192.0.2.2","","fe80::1%eth0"}) {
            var request=new MockHttpServletRequest();request.setRemoteAddr("192.0.2.8");request.addHeader(ClientAddressResolver.HEADER,value);
            assertEquals("192.0.2.8",resolver.resolve(request));
        }
        var request=new MockHttpServletRequest();request.setRemoteAddr("192.0.2.8");assertEquals("192.0.2.8",resolver.resolve(request));
        request.addHeader(ClientAddressResolver.HEADER,"203.0.113.1");request.addHeader(ClientAddressResolver.HEADER,"203.0.113.2");
        assertEquals("192.0.2.8",resolver.resolve(request));
    }
    @Test void proxyConfigurationRejectsNamesAndWildcards() {
        for(var value:new String[]{"*","localhost","0.0.0.0/0"})assertThrows(IllegalArgumentException.class,()->new ClientAddressResolver(value));
    }
}
