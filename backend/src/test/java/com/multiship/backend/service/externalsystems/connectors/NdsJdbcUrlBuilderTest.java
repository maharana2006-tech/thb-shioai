package com.multiship.backend.service.externalsystems.connectors;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NdsJdbcUrlBuilderTest {

    @Test
    void buildsExactDescriptorStringForNdsServer() {
        // Locked exact-string test — this is the wire format NDS's
        // documentation confirms, and future regressions in URL
        // formatting would break in obscure ways at pool startup.
        String url = NdsJdbcUrlBuilder.build("192.168.3.8", 1521, "tb10g", "DEDICATED");
        assertEquals(
                "jdbc:oracle:thin:@(DESCRIPTION="
                        + "(ADDRESS_LIST=(ADDRESS=(PROTOCOL=TCP)(HOST=192.168.3.8)(PORT=1521)))"
                        + "(CONNECT_DATA=(SERVER=DEDICATED)(SERVICE_NAME=tb10g)))",
                url);
    }

    @Test
    void defaultsServerModeToDedicatedWhenBlank() {
        String url = NdsJdbcUrlBuilder.build("h", 1, "s", null);
        assertTrue(url.contains("(SERVER=DEDICATED)"));

        url = NdsJdbcUrlBuilder.build("h", 1, "s", "");
        assertTrue(url.contains("(SERVER=DEDICATED)"));

        url = NdsJdbcUrlBuilder.build("h", 1, "s", "   ");
        assertTrue(url.contains("(SERVER=DEDICATED)"));
    }

    @Test
    void upperCasesServerMode() {
        String url = NdsJdbcUrlBuilder.build("h", 1, "s", "shared");
        assertTrue(url.contains("(SERVER=SHARED)"));
    }

    @Test
    void rejectsBlankHost() {
        assertThrows(IllegalArgumentException.class,
                () -> NdsJdbcUrlBuilder.build(null, 1521, "tb10g", "DEDICATED"));
        assertThrows(IllegalArgumentException.class,
                () -> NdsJdbcUrlBuilder.build("", 1521, "tb10g", "DEDICATED"));
    }

    @Test
    void rejectsBlankServiceName() {
        assertThrows(IllegalArgumentException.class,
                () -> NdsJdbcUrlBuilder.build("h", 1521, null, "DEDICATED"));
        assertThrows(IllegalArgumentException.class,
                () -> NdsJdbcUrlBuilder.build("h", 1521, "  ", "DEDICATED"));
    }

    @Test
    void rejectsBadPort() {
        assertThrows(IllegalArgumentException.class,
                () -> NdsJdbcUrlBuilder.build("h", 0, "tb10g", "DEDICATED"));
        assertThrows(IllegalArgumentException.class,
                () -> NdsJdbcUrlBuilder.build("h", -1, "tb10g", "DEDICATED"));
        assertThrows(IllegalArgumentException.class,
                () -> NdsJdbcUrlBuilder.build("h", 70_000, "tb10g", "DEDICATED"));
    }

    @Test
    void trimsHostAndServiceName() {
        String url = NdsJdbcUrlBuilder.build("  10.0.0.1  ", 1521, "  svc  ", "DEDICATED");
        assertTrue(url.contains("(HOST=10.0.0.1)"));
        assertTrue(url.contains("(SERVICE_NAME=svc)"));
    }
}
