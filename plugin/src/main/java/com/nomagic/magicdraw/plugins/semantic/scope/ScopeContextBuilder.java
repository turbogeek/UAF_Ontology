package com.nomagic.magicdraw.plugins.semantic.scope;

import com.nomagic.magicdraw.plugins.semantic.align.ScopeContext;
import com.nomagic.uml2.ext.jmi.helpers.StereotypesHelper;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.NamedElement;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Type;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.TypedElement;
import com.nomagic.uml2.ext.magicdraw.mdprofiles.Stereotype;

import java.util.ArrayList;
import java.util.List;

/**
 * Derives a {@link ScopeContext} for the SELECTED model element - the Cameo-bound half of
 * scope-aware search (UC-2.8). It reads three things off the element and hands them to the pure,
 * unit-tested derivations in {@link ScopeContext}:
 * <ul>
 *   <li><b>construct kind</b> from the metaclass ({@code getHumanType()}), falling back to the
 *       applied stereotype names - a SysML {@code Activity} -> BEHAVIOR, a {@code Part Property}
 *       -> STRUCTURE;</li>
 *   <li><b>UAF layer</b> from the applied stereotype names + the owning-package names -
 *       {@code ResourcePerformer} / an "Operational" package -> the layer's namespaces;</li>
 *   <li><b>context terms</b> - the element's own TYPE (a part {@code engine} typed {@code V8
 *       Engine}), its OWNER's name, and SIBLING feature names - the structural disambiguators.</li>
 * </ul>
 *
 * <p>Model reads are EDT-only in Cameo; call this from the selection handler (already on the EDT).
 * It NEVER throws - any failure yields {@link ScopeContext#EMPTY}, so suggestions simply fall back
 * to the non-scoped ranking.</p>
 * Trace: design/use_cases.md UC-2.8
 */
public final class ScopeContextBuilder {

    /** Cap sibling terms so a huge container cannot flood the context signal. */
    private static final int MAX_SIBLINGS = 6;

    private ScopeContextBuilder() {
    }

    public static ScopeContext build(Element element) {
        if (element == null) {
            return ScopeContext.EMPTY;
        }
        try {
            List<String> stereotypeNames = new ArrayList<>();
            for (Stereotype s : StereotypesHelper.getStereotypes(element)) {
                if (s != null && s.getName() != null) {
                    stereotypeNames.add(s.getName());
                }
            }
            String kind = deriveKind(element, stereotypeNames);
            String layer = ScopeContext.deriveLayer(stereotypeNames, ownerNames(element));
            List<ScopeContext.ContextTerm> terms = contextTerms(element);
            ScopeContext scope = new ScopeContext(layer, kind, terms);
            return scope.isEmpty() ? ScopeContext.EMPTY : scope;
        } catch (Throwable t) {
            // Scope is an enhancement; never let a model-traversal quirk break suggestions.
            return ScopeContext.EMPTY;
        }
    }

    /** Construct kind from the metaclass, then from stereotype base names as a fallback. */
    private static String deriveKind(Element element, List<String> stereotypeNames) {
        String fromMeta = ScopeContext.deriveConstructKind(safeHumanType(element));
        if (fromMeta != null) {
            return fromMeta;
        }
        for (String s : stereotypeNames) {
            String k = ScopeContext.deriveConstructKind(s);
            if (k != null) {
                return k;
            }
        }
        return null;
    }

    private static String safeHumanType(Element element) {
        try {
            return element.getHumanType();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Owner + ancestor NAMES (packages/blocks) up to a few levels - fuels layer inference. */
    private static List<String> ownerNames(Element element) {
        List<String> names = new ArrayList<>();
        Element owner = safeOwner(element);
        int guard = 0;
        while (owner != null && guard++ < 8) {
            if (owner instanceof NamedElement named && named.getName() != null && !named.getName().isBlank()) {
                names.add(named.getName());
            }
            owner = safeOwner(owner);
        }
        return names;
    }

    /** The weighted context terms: the element's own type, its owner, and sibling features. */
    private static List<ScopeContext.ContextTerm> contextTerms(Element element) {
        List<ScopeContext.ContextTerm> terms = new ArrayList<>();
        // TYPE - strongest disambiguator (a part 'engine' typed 'V8 Engine').
        if (element instanceof TypedElement typed) {
            Type type = safeType(typed);
            addTerm(terms, type == null ? null : type.getName(), ScopeContext.Role.TYPE);
        }
        // OWNER - the containing element's name (the SUV that owns the engine).
        Element owner = safeOwner(element);
        if (owner instanceof NamedElement named) {
            addTerm(terms, named.getName(), ScopeContext.Role.OWNER);
            // SIBLINGS - other named features under the same owner.
            try {
                int added = 0;
                for (Element sib : owner.getOwnedElement()) {
                    if (added >= MAX_SIBLINGS) {
                        break;
                    }
                    if (sib != element && sib instanceof NamedElement sn
                            && sn.getName() != null && !sn.getName().isBlank()) {
                        addTerm(terms, sn.getName(), ScopeContext.Role.SIBLING);
                        added++;
                    }
                }
            } catch (Throwable ignored) {
                // sibling enumeration is best-effort
            }
        }
        return terms;
    }

    private static void addTerm(List<ScopeContext.ContextTerm> terms, String text, ScopeContext.Role role) {
        if (text != null && !text.isBlank()) {
            terms.add(new ScopeContext.ContextTerm(text.trim(), role));
        }
    }

    private static Element safeOwner(Element element) {
        try {
            return element.getOwner();
        } catch (Throwable t) {
            return null;
        }
    }

    private static Type safeType(TypedElement typed) {
        try {
            return typed.getType();
        } catch (Throwable t) {
            return null;
        }
    }
}
