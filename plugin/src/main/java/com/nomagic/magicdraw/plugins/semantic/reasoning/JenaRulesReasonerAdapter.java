package com.nomagic.magicdraw.plugins.semantic.reasoning;

import org.apache.jena.rdf.model.InfModel;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.reasoner.ReasonerRegistry;
import org.apache.jena.reasoner.ValidityReport;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Default reasoner: Jena's built-in OWL rule reasoner. Covers subclass/subproperty,
 * domain/range, and owl:disjointWith - enough for the flagship "actual element in a
 * logical view" detection. Not a complete DL engine; candidates like ELK/HermiT slot
 * in beside it via ReasonerAdapter when benchmarking begins.
 */
public final class JenaRulesReasonerAdapter implements ReasonerAdapter {

    public static final String ID = "jena-rules";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public ConsistencyResult checkConsistency(Model abox, Model tbox) {
        Model union = ModelFactory.createDefaultModel();
        if (abox != null) {
            union.add(abox);
        }
        if (tbox != null) {
            union.add(tbox);
        }
        InfModel inf = ModelFactory.createInfModel(ReasonerRegistry.getOWLReasoner(), union);
        ValidityReport report = inf.validate();
        List<String> messages = new ArrayList<>();
        if (!report.isValid()) {
            Iterator<ValidityReport.Report> it = report.getReports();
            while (it.hasNext()) {
                messages.add(it.next().getDescription());
            }
        }
        return new ConsistencyResult(report.isValid(), messages);
    }
}
