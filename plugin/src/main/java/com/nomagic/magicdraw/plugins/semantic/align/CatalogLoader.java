package com.nomagic.magicdraw.plugins.semantic.align;

import com.nomagic.magicdraw.plugins.semantic.DiagnosticLog;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.rdf.model.StmtIterator;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.vocabulary.OWL;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.apache.log4j.Logger;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Loads the alignment catalog (the .ttl ontologies deployed under the plugin's
 * catalog/ directory, or -Dsemantic.plugin.catalog override) into a ConceptIndex.
 * Runs once on a background thread at plugin init; per the project's cache rule the
 * result is validated non-empty and its statistics are journaled so an empty or
 * misdeployed catalog is loud, never silent.
 * Trace: v3 plan section 1
 */
public final class CatalogLoader {

    private static final Logger log = Logger.getLogger(CatalogLoader.class);
    private static final String CATALOG_PROPERTY = "semantic.plugin.catalog";
    private static final String SKOS_NS = "http://www.w3.org/2004/02/skos/core#";

    // Private constructor to prevent instantiation of utility class
    private CatalogLoader() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    /**
     * User catalog directory for ON-DEMAND ontology imports (owner scenario: medical
     * device / drug manufacture programs pulling in governance, certification, and
     * approval ontologies as needed). Lives under the diagnostic home so redeploys
     * never wipe it; drop a .ttl here and hit /catalog/reload - no restart.
     */
    public static File resolveUserCatalogDirectory() {
        File dir = DiagnosticLog.getLogDirectory().resolve("catalog").toFile();
        if (!dir.exists() && !dir.mkdirs()) {
            log.warn("Could not create user catalog directory: " + dir);
        }
        return dir;
    }

    /** Merges the shipped plugin catalog with the user's on-demand imports. */
    public static LoadedCatalog loadMerged(File pluginDirectory) {
        LoadedCatalog shipped = loadAll(resolveCatalogDirectory(pluginDirectory));
        File userDir = resolveUserCatalogDirectory();
        File[] userFiles = userDir.listFiles((d, name) -> name.toLowerCase().endsWith(".ttl"));
        if (userFiles == null || userFiles.length == 0) {
            return shipped;
        }
        LoadedCatalog user = loadAll(userDir);
        for (ConceptEntry entry : user.index().entries()) {
            shipped.index().add(entry);
        }
        shipped.model().add(user.model());
        DiagnosticLog.event("CATALOG", "User catalog merged: +" + user.index().size()
                + " concepts from " + userDir);
        return shipped;
    }

    public static File resolveCatalogDirectory(File pluginDirectory) {
        String override = System.getProperty(CATALOG_PROPERTY);
        if (override != null && !override.isBlank()) {
            File dir = new File(override);
            if (dir.isDirectory()) {
                return dir;
            }
        }
        if (pluginDirectory != null) {
            File dir = new File(pluginDirectory, "catalog");
            if (dir.isDirectory()) {
                return dir;
            }
        }
        return null;
    }

    /** Index plus the union TBox model (shared by SPARQL and reasoning views). */
    public record LoadedCatalog(ConceptIndex index, Model model) {
    }

    /** Loads every .ttl in the catalog directory. Never throws; problems are journaled. */
    public static ConceptIndex load(File catalogDirectory) {
        return loadAll(catalogDirectory).index();
    }

    /** Single-pass load producing both the concept index and the union TBox model. */
    public static LoadedCatalog loadAll(File catalogDirectory) {
        ConceptIndex index = new ConceptIndex();
        Model union = ModelFactory.createDefaultModel();
        if (catalogDirectory == null) {
            DiagnosticLog.event("ERROR", "Concept catalog directory not found - suggestions disabled. "
                    + "Deploy catalog/ with the plugin or set -D" + CATALOG_PROPERTY);
            return new LoadedCatalog(index, union);
        }
        File[] files = catalogDirectory.listFiles((d, name) -> name.toLowerCase().endsWith(".ttl"));
        if (files == null || files.length == 0) {
            DiagnosticLog.event("ERROR", "Concept catalog is empty: " + catalogDirectory);
            return new LoadedCatalog(index, union);
        }
        StringBuilder stats = new StringBuilder();
        for (File file : files) {
            try {
                int before = index.size();
                loadFile(file, index, union);
                stats.append(file.getName()).append('=').append(index.size() - before).append(' ');
            } catch (Exception e) {
                log.error("Catalog file failed to load: " + file, e);
                DiagnosticLog.event("ERROR", "Catalog file failed to load: " + file + " -> " + e);
            }
        }
        // A silently empty index would degrade the UI to useless with no evidence.
        if (index.isEmpty()) {
            DiagnosticLog.event("ERROR", "Concept index is EMPTY after loading " + files.length
                    + " catalog files from " + catalogDirectory);
        } else {
            DiagnosticLog.event("CATALOG", "Concept index ready: " + index.size()
                    + " concepts, " + union.size() + " TBox triples | " + stats.toString().trim());
        }
        return new LoadedCatalog(index, union);
    }

    private static void loadFile(File file, ConceptIndex index, Model union) {
        Model model = ModelFactory.createDefaultModel();
        RDFDataMgr.read(model, file.getAbsolutePath());
        union.add(model);
        union.setNsPrefixes(model.getNsPrefixMap());
        String ontologyId = file.getName().replaceFirst("\\.ttl$", "");
        Map<String, String> nsToPrefix = invert(model.getNsPrefixMap());

        StmtIterator classes = model.listStatements(null, RDF.type, OWL.Class);
        while (classes.hasNext()) {
            Resource subject = classes.next().getSubject();
            if (!subject.isURIResource()) {
                continue; // anonymous restrictions are not alignment targets
            }
            String iri = subject.getURI();
            String ns = subject.getNameSpace();
            String prefix = nsToPrefix.getOrDefault(ns, ontologyId);
            String local = subject.getLocalName();

            // W3C ORG carries multilingual labels; a French "organisation"@fr must not
            // become the primary label or exact-match ranking silently breaks. English
            // (or untagged) wins; every other language becomes a searchable alias.
            List<String> allLabels = literals(subject, RDFS.label.getURI(), model);
            String label = preferEnglish(subject, RDFS.label);
            if (label == null || label.isBlank()) {
                label = local;
            }
            List<String> altLabels = new ArrayList<>(allLabels);
            altLabels.remove(label);
            altLabels.addAll(literals(subject, SKOS_NS + "prefLabel", model));
            altLabels.addAll(literals(subject, SKOS_NS + "altLabel", model));
            String comment = preferEnglish(subject, RDFS.comment);

            Set<String> tokens = new HashSet<>(ConceptIndex.tokenize(label));
            tokens.addAll(ConceptIndex.tokenize(local));
            for (String alt : altLabels) {
                tokens.addAll(ConceptIndex.tokenize(alt));
            }

            index.add(new ConceptEntry(iri, prefix + ":" + local, label,
                    List.copyOf(altLabels), comment == null ? "" : comment,
                    ontologyId, prefix, Set.copyOf(tokens)));
        }
    }

    private static Map<String, String> invert(Map<String, String> prefixToNs) {
        Map<String, String> result = new java.util.HashMap<>();
        prefixToNs.forEach((prefix, ns) -> {
            if (!prefix.isBlank()) {
                result.put(ns, prefix);
            }
        });
        return result;
    }

    /** First English or untagged literal, falling back to any language. */
    private static String preferEnglish(Resource subject, org.apache.jena.rdf.model.Property property) {
        String fallback = null;
        StmtIterator it = subject.listProperties(property);
        while (it.hasNext()) {
            RDFNode node = it.next().getObject();
            if (!node.isLiteral()) {
                continue;
            }
            String lang = node.asLiteral().getLanguage();
            if (lang == null || lang.isEmpty() || lang.startsWith("en")) {
                return node.asLiteral().getString();
            }
            if (fallback == null) {
                fallback = node.asLiteral().getString();
            }
        }
        return fallback;
    }

    static List<String> literals(Resource subject, String propertyIri, Model model) {
        List<String> result = new ArrayList<>();
        StmtIterator it = subject.listProperties(model.createProperty(propertyIri));
        while (it.hasNext()) {
            Statement statement = it.next();
            if (statement.getObject().isLiteral()) {
                result.add(statement.getObject().asLiteral().getString());
            }
        }
        return result;
    }
}
