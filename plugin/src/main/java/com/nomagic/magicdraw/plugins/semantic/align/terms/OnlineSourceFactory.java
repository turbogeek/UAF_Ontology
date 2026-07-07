package com.nomagic.magicdraw.plugins.semantic.align.terms;

import org.apache.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Builds the list of ONLINE term sources (OLS-family + OntoPortal) from configuration, with NO
 * dependency on any Cameo type - so it is unit-testable off-line and, crucially, cannot drag a
 * static-initialization-order or config fault into the plugin's {@code <clinit>}.
 *
 * <p>History: this logic used to live in {@code SemanticAlignmentPlugin}'s static initializer,
 * where the {@code ONLINE_SOURCES} field ran {@code buildOnlineSources()} before the
 * {@code ONTOPORTAL_CONFIG} field was initialized (Java runs field initializers in textual
 * order), throwing an NPE in {@code <clinit>} that failed the WHOLE plugin to load. Extracting
 * it here (a) removes that ordering hazard, (b) makes {@link #build(Properties)} null-safe, and
 * (c) is covered by a regression test that passes {@code null} config - the exact fault. Online
 * sources are optional (the plugin works fully offline), so this method NEVER throws.</p>
 *
 * Trace: owner directive - no startup exceptions; code-review to prevent them.
 */
public final class OnlineSourceFactory {

    private static final Logger log = Logger.getLogger(OnlineSourceFactory.class);

    private static final String TIB_ENDPOINT = "https://api.terminology.tib.eu/api";

    private OnlineSourceFactory() {
    }

    /**
     * @param ontoPortalConfig portal keys (portal.apikey / .url / .ontologies); may be {@code null}
     * @return keyless OLS-family sources (EBI OLS4 + TIB) plus any OntoPortal portals whose apikey
     *         is configured (via the properties or a {@code -Dsemantic.plugin.ontoportal.*} system
     *         property). Never throws.
     */
    public static List<TermSource> build(Properties ontoPortalConfig) {
        List<TermSource> sources = new ArrayList<>();
        try {
            // OLS-family (keyless): EBI OLS4 + TIB engineering terminology, or an override list.
            String cfg = System.getProperty("semantic.plugin.ols.endpoints");
            if (cfg != null && !cfg.isBlank()) {
                for (String base : cfg.split(",")) {
                    if (!base.isBlank()) {
                        sources.add(new Ols4TermSource(base.trim()));
                    }
                }
            } else {
                sources.add(new Ols4TermSource()); // EBI OLS4 (own default / -Dsemantic.plugin.ols4.baseurl)
                sources.add(new Ols4TermSource(TIB_ENDPOINT)); // TIB (engineering/physics/materials)
            }
            // OntoPortal family (one adapter, N portals): each enabled ONLY when its apikey is set.
            addOntoPortal(sources, ontoPortalConfig, "bioportal", "https://data.bioontology.org");
            addOntoPortal(sources, ontoPortalConfig, "industryportal", "https://industryportal.enit.fr");
            addOntoPortal(sources, ontoPortalConfig, "matportal", "https://matportal.org");
        } catch (Throwable t) {
            // Optional feature: a config/network fault must never break plugin load.
            log.warn("Online source setup partially failed (plugin continues offline-capable): " + t);
        }
        return sources;
    }

    private static void addOntoPortal(List<TermSource> sources, Properties config,
                                      String portal, String defaultUrl) {
        String key = prop(config, portal, "apikey", null);
        if (key == null || key.isBlank()) {
            return; // portal not enabled - no key configured
        }
        String url = prop(config, portal, "url", defaultUrl);
        if (url == null || url.isBlank()) {
            return;
        }
        String onts = prop(config, portal, "ontologies", null);
        sources.add(new OntoPortalTermSource(portal, url, key, onts));
        log.info("OntoPortal term source enabled: " + portal + " @ " + url);
    }

    /** System property first, then the (nullable) config file, then the fallback. Null-safe. */
    private static String prop(Properties config, String portal, String suffix, String fallback) {
        String sys = System.getProperty("semantic.plugin.ontoportal." + portal + "." + suffix);
        if (sys != null && !sys.isBlank()) {
            return sys;
        }
        String fromFile = config == null ? null : config.getProperty(portal + "." + suffix);
        return (fromFile != null && !fromFile.isBlank()) ? fromFile : fallback;
    }
}
