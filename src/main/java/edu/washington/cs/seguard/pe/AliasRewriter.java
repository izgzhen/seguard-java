/*
    Replace each alias with its original
 */

package edu.washington.cs.seguard.pe;

import edu.washington.cs.seguard.Conditions;
import edu.washington.cs.seguard.Util;
import lombok.val;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soot.*;
import soot.jimple.Stmt;
import soot.jimple.toolkits.pointer.LocalMustAliasAnalysis;
import soot.toolkits.graph.BriefUnitGraph;
import soot.toolkits.graph.UnitGraph;

import java.util.Map;

public class AliasRewriter extends SceneTransformer {
    private Logger logger = LoggerFactory.getLogger(AliasRewriter.class);

    @Override
    protected void internalTransform(String phaseName, Map<String, String> options) {
        val timer = new Util.SimpleTimer();
        for (SootClass c : Scene.v().getClasses()) {
            if (Conditions.blacklisted(c) || c.getName().contains("dummyMain") || c.isJavaLibraryClass() || (!c.isApplicationClass())) {
                continue;
            }
            logger.info("Alias transforming class {}", c.getName());
            for (SootMethod m : c.getMethods()) {
                if (m.isJavaLibraryMethod() || !m.hasActiveBody()) {
                    continue;
                }
                Body b = m.getActiveBody();
                UnitGraph ug = new BriefUnitGraph(b);

                LocalMustAliasAnalysis analysis = new LocalMustAliasAnalysis(ug);

                for (Local l1 : b.getLocals()) {
                    for (Local l2 : b.getLocals()) {
                        if (l1.equals(l2)) {
                            continue;
                        }
                        for (Unit u : b.getUnits()) {
                            if (analysis.mustAlias(l1, (Stmt) u, l2, (Stmt) u)) {
                                for (ValueBox box : u.getUseBoxes()) {
                                    if (box.getValue().equals(l2)) {
                                        box.setValue(l1);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
