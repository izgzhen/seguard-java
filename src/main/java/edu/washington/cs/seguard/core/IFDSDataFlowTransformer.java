package edu.washington.cs.seguard.core;

import edu.washington.cs.seguard.Abstraction;
import edu.washington.cs.seguard.Conditions;
import edu.washington.cs.seguard.Config;
import heros.InterproceduralCFG;
import heros.solver.IFDSSolver;
import lombok.val;
import soot.*;
import soot.jimple.toolkits.ide.icfg.JimpleBasedInterproceduralCFG;
import soot.toolkits.scalar.Pair;

import java.io.FileNotFoundException;
import java.io.PrintWriter;
import java.util.Map;
import java.util.Set;

public class IFDSDataFlowTransformer extends SceneTransformer {
    IFDSSolver<Unit, Pair<Value, Set<Abstraction>>, SootMethod, InterproceduralCFG<Unit, SootMethod>> solver;

    private Conditions conditions;
    private Config config;
    public IFDSDataFlowTransformer(Conditions conditions, Config config) {
        this.conditions = conditions;
        this.config = config;
    }

    @Override
    protected void internalTransform(String phaseName, Map<String, String> options) {
        val icfg = new JimpleBasedInterproceduralCFG();
        val analysis = new DataFlowAnalysis(icfg, conditions, config);
        solver = new IFDSSolver<>(analysis);
        System.out.println(">>>>>>>>>>>>>>>>>>>>>>>>>>>>>> Starting solver");
        solver.solve();
        System.out.println(">>>>>>>>>>>>>>>>>>>>>>>>>>>>>> Done");

        if (config.isDebug()) {
            try {
                val printWriter = new PrintWriter(config.getAbstractionDumpPath());
                for (val m : analysis.getVisitedMethods()) {
                    printWriter.println("====== Method " + m.getSignature() + " =======");
                    printWriter.println(m.getActiveBody());
                    for (val unit : m.getActiveBody().getUnits()) {
                        val abstractions = analysis.getUnitAbstractionMap().get(unit);
                        val abstractionsAfter = analysis.getUnitAbstractionAfterMap().get(unit);
                        if (abstractions != null) {
                            for (val value : abstractions.entrySet()) {
                                for (val abstraction : value.getValue()) {
                                    printWriter.println("\t\t" + value.getKey() + ": " + abstraction);
                                }
                            }
                        }
                        if ((abstractions != null && abstractions.entrySet().size() > 0) ||
                                (abstractionsAfter != null && abstractionsAfter.entrySet().size() > 0)) {
                            printWriter.println("\tUnit: " + unit);
                        }
                        if (abstractionsAfter != null) {
                            for (val value : abstractionsAfter.entrySet()) {
                                for (val abstraction : value.getValue()) {
                                    printWriter.println("\t\t" + value.getKey() + ": " + abstraction);
                                }
                            }
                        }
                        printWriter.println();
                    }
                }
                printWriter.close();
            } catch (FileNotFoundException e) {
                System.err.println(e.toString());
            }
        }
    }

    public IFDSSolver<Unit, Pair<Value, Set<Abstraction>>, SootMethod, InterproceduralCFG<Unit, SootMethod>> getSolver() {
        return solver;
    }
}
