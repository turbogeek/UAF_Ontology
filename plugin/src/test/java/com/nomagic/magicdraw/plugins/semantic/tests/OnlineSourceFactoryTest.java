package com.nomagic.magicdraw.plugins.semantic.tests;

import com.nomagic.magicdraw.plugins.semantic.align.terms.OnlineSourceFactory;
import com.nomagic.magicdraw.plugins.semantic.align.terms.TermSource;
import org.junit.Test;

import java.util.List;
import java.util.Properties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Regression test for the static-initialization NPE that failed the WHOLE plugin to load: the
 * plugin's {@code ONLINE_SOURCES} field ran before {@code ONTOPORTAL_CONFIG}, so the online-source
 * builder read a {@code null} Properties and threw an NPE in {@code <clinit>} ("Failed to create
 * plugin"). The logic now lives in {@link OnlineSourceFactory}, which is null-safe and must NEVER
 * throw - proven here by passing {@code null} config (the exact fault) plus the enable/disable
 * paths. This is the code-review guard the owner asked for against startup exceptions.
 */
public class OnlineSourceFactoryTest {

    @Test
    public void testNullConfigDoesNotThrowAndReturnsKeylessOls() {
        // The exact <clinit> fault: config not yet initialized -> null.
        List<TermSource> sources = OnlineSourceFactory.build(null);
        assertNotNull("must never return null", sources);
        assertEquals("two keyless OLS-family sources (EBI OLS4 + TIB), no portals without keys",
                2, sources.size());
    }

    @Test
    public void testEmptyConfigYieldsOnlyKeylessOls() {
        List<TermSource> sources = OnlineSourceFactory.build(new Properties());
        assertEquals(2, sources.size());
    }

    @Test
    public void testPortalApiKeyEnablesThatPortal() {
        Properties config = new Properties();
        config.setProperty("bioportal.apikey", "test-key-123");
        List<TermSource> sources = OnlineSourceFactory.build(config);
        assertEquals("2 OLS + 1 enabled OntoPortal", 3, sources.size());
    }

    @Test
    public void testBlankApiKeyDoesNotEnablePortal() {
        Properties config = new Properties();
        config.setProperty("matportal.apikey", "   ");
        assertEquals("blank key -> portal stays disabled", 2, OnlineSourceFactory.build(config).size());
    }

    @Test
    public void testOlsEndpointsSystemPropertyOverridesDefaults() {
        String prev = System.getProperty("semantic.plugin.ols.endpoints");
        try {
            System.setProperty("semantic.plugin.ols.endpoints", "https://ols.example.org/api");
            List<TermSource> sources = OnlineSourceFactory.build(null);
            assertEquals("single overridden OLS endpoint", 1, sources.size());
            assertTrue(sources.get(0) != null);
        } finally {
            if (prev == null) {
                System.clearProperty("semantic.plugin.ols.endpoints");
            } else {
                System.setProperty("semantic.plugin.ols.endpoints", prev);
            }
        }
    }
}
