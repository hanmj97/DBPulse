package com.dbpulse.api;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class QueryTextLocalUiSecurityTest {
    @Test void acceptsLocalTrustedUIAndRejectsOtherOrigins() {
        var request=new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.setServerName("127.0.0.1");
        request.addHeader("Sec-Fetch-Site","same-origin");
        assertTrue(QueryTextController.isLocalUiRequest(request));
        request.addHeader("Origin","https://another-site.invalid");
        assertFalse(QueryTextController.isLocalUiRequest(request));
    }

    @Test void rejectsRemoteRequests() {
        var request=new MockHttpServletRequest();
        request.setRemoteAddr("192.168.1.12");
        request.setServerName("127.0.0.1");
        assertFalse(QueryTextController.isLocalUiRequest(request));
    }
}
