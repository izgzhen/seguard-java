/*
  Program -> Program Facts -> Concrete Flow Graph
  TODO: decouple PF and ConcFG
  @author: Zhen Zhang
 */


package edu.washington.cs.seguard.core;
import edu.washington.cs.seguard.*;
import edu.washington.cs.seguard.pe.AliasRewriter;
import edu.washington.cs.seguard.util.StatManager;
import lombok.SneakyThrows;
import lombok.val;
import soot.*;
import soot.jimple.*;
import soot.jimple.infoflow.entryPointCreators.DefaultEntryPointCreator;
import soot.jimple.toolkits.callgraph.CallGraph;
import soot.jimple.toolkits.callgraph.Edge;
import soot.toolkits.graph.*;
import soot.toolkits.scalar.*;

import java.io.FileNotFoundException;
import java.io.PrintWriter;
import java.util.*;

import static edu.washington.cs.seguard.util.StatKey.*;

import org.apache.commons.lang3.StringEscapeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class FlowGraph {
    private Logger logger = LoggerFactory.getLogger(FlowGraph.class);

    final private Conditions conditions;
    final private StatManager statManager;
    final private BetterDot dot;

    public FlowGraph(Conditions conditions, StatManager statManager, BetterDot dot) {
        this.conditions = conditions;
        this.statManager = statManager;
        this.dot = dot;
    }

    private static SootMethod getInvokedMethod(Unit u) {
        Stmt s = (Stmt) u;
        if (s.containsInvokeExpr()) {
            InvokeExpr e = s.getInvokeExpr();
            return e.getMethod();
        }
        return null;
    }

    /**
     * Construct flow-graph
     */
    public void Main() {
        Scene.v().loadNecessaryClasses();

        if (statManager != null) {
            statManager.put(BASIC_CLASSES, Scene.v().getBasicClasses().size());
            statManager.put(CLASSES, Scene.v().getClasses().size());
            statManager.put(LIBRARY_CLASSES, Scene.v().getLibraryClasses().size());
            statManager.put(PHANTON_CLASSES, Scene.v().getPhantomClasses().size());
        }

        val staticStringMap = setEntrypointsAndGetStaticStringMap();
        collectStaticStringFields(staticStringMap);

        val transformer = new IFDSDataFlowTransformer(conditions, Config.v());

        // Resolve some aliasing by instrumentation to ease the next phase of analysis
        PackManager.v().getPack("wjtp").add(new Transform("wjtp.aliasrewriter", new AliasRewriter()));
        // Run our IFDS/IDE data-flow analysis (DFA)
        PackManager.v().getPack("wjtp").add(new Transform("wjtp.herosifds", transformer));
        // Collect results into the flow-graph by first analyzing the data-flow facts from previous FDA and also
        // static facts from call-graph
        PackManager.v().getPack("wjtp").add(new Transform("wjtp.myTransform", new SceneTransformer() {
            @SneakyThrows
            @Override
            protected void internalTransform(String phaseName, Map<String, String> options) {
                // Collect some data-flow facts into flow-graph
                collectDataflowFacts(transformer);

                // Collect other facts into the flow-graph (dot)
                // FIXME: Non-det
                processOtherFacts();
            }
        }));
        logger.info("Run Soot packs...");
        SootOptionManager.Manager().sootRunPacks();
    }

    private void collectDataflowFacts(IFDSDataFlowTransformer transformer) {
        for (SootClass cls : Scene.v().getClasses()) {
            for (SootMethod m : cls.getMethods()) {
                if (cls.isJavaLibraryClass() || !m.hasActiveBody()) {
                    continue;
                }
                if (Conditions.blacklisted(m)) {
                    continue;
                }
                Body b = m.getActiveBody();
                for (Unit u : b.getUnits()) {
                    Stmt s = (Stmt) u;
                    if (s.containsInvokeExpr()) {
                        SootMethod invoked = s.getInvokeExpr().getMethod();
                        if (conditions.isSensitiveMethod(invoked)) {
                            for (ValueBox vbox : u.getUseBoxes()) {
                                for (Pair<Value, Set<Abstraction>> p : transformer.solver.ifdsResultsAt(u)) {
                                    if (vbox.getValue().equals(p.getO1())) {
                                        for (Abstraction abstraction : p.getO2()) {
                                            SootUtils.processDataFlowFacts(abstraction, dot, invoked);
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

    private Map<SootClass, Map<String, String>> setEntrypointsAndGetStaticStringMap() {
        Map<SootClass, Map<String, String>> staticStringMap = new TreeMap<>(Comparator.comparing(SootClass::toString));

        // Collecting interesting methods and static attribute strings by iterating all classes
        Set<SootMethod> ms = new TreeSet<>(Comparator.comparing(SootMethod::toString));
        for (SootClass appCls : Scene.v().getApplicationClasses()) {
            if (!appCls.isConcrete()) {
                continue;
            }
            Scene.v().forceResolve(appCls.getName(), SootClass.BODIES);
            staticStringMap.put(appCls, Util.getStaticStrings(appCls));
            List<SootMethod> methods = new ArrayList<>(appCls.getMethods());
            for (final SootMethod appMethod : methods) {
                try {
                    appMethod.retrieveActiveBody();
                } catch (RuntimeException e) {
                    continue;
                }
                Body b = appMethod.getActiveBody();
                if (b != null) {
                    ms.add(appMethod);
                }
            }
        }

        if (statManager != null) {
            statManager.put(MS_NUM, ms.size());
            statManager.put(ORIGINAL_NUM_ENTRYPOINTS, Scene.v().getEntryPoints().size());
        }
        Set<String> entryPoints = new TreeSet<>();
        // for each concrete application class's method with body
        for (SootMethod m : ms) {
            // if the application class inherits some library class, print the inherited library class
            // and count such type of application classes. we might consider them as priority
            SootClass cls = m.getDeclaringClass();
            if (Conditions.blacklisted(cls) || Conditions.blacklisted(m)) {
                continue;
            }
            if (cls.hasSuperclass() || cls.getInterfaceCount() > 0) {
                if (conditions.isSensitiveEntrypointParent(cls.getSuperclass())) {
                    entryPoints.add(m.getSignature());
                    continue;
                }
                for (SootClass iface : cls.getInterfaces()) {
                    if (conditions.isSensitiveEntrypointParent(iface)) {
                        entryPoints.add(m.getSignature());
                        break;
                    }
                }
            }
        }

        if (statManager != null) {
            statManager.put(NEW_NUM_ENTRYPOINTS, entryPoints.size());
        }

        val entryPointCreator = new DefaultEntryPointCreator(entryPoints);
        val dummyMain = entryPointCreator.createDummyMain();

        Scene.v().setEntryPoints(Collections.singletonList(dummyMain));
        return staticStringMap;
    }

    private void processOtherFacts() throws FileNotFoundException {
        val cg = Scene.v().getCallGraph();
        if (statManager != null) {
            statManager.put(CG_SIZE, cg.size());
        }
        if (Config.v().isDebug()) {
            val printWriter = new PrintWriter(Config.v().getCallGraphDumpPath());
            printWriter.write(cg.toString());
            printWriter.close();
        }

        collectCallGraphRelationships(cg);

        // Collect fact around each method
        for (SootClass cls : Scene.v().getClasses()) {
            if (!cls.isApplicationClass()) {
                continue;
            }
            for (SootMethod method : cls.getMethods()) {
                if (method.isJavaLibraryMethod()) { continue; }
                if (Conditions.blacklisted(method)) { continue; }
                try {
                    method.retrieveActiveBody();
                } catch (RuntimeException e) {
                    continue;
                }

                collectSensitiveInheritance(method);

                Body b = method.getActiveBody();
                if (b != null) {
                    collectIntraprocFacts(method, b);
                } else {
                    logger.warn("Failed to getActiveBody of {}", method.getSignature());
                }
            }
        }
    }

    private void collectIntraprocFacts(SootMethod method, Body b) {
        UnitGraph unitGraph = new BriefUnitGraph(b);

        // API happens-before
        DirectedGraph<Unit> dominanceDirectedGraph = (new MHGDominatorsFinder<>(unitGraph)).getGraph();

        // Local definitions
        LocalDefs localDefs = new SmartLocalDefs(unitGraph, new SimpleLiveLocals(unitGraph));

        for (Unit stmt : unitGraph) {
            val currentInvoked = getInvokedMethod(stmt);
            if (currentInvoked == null) {
                collectReturnDataflow(method, localDefs, stmt);
                continue;
            }

            // TODO: fix the reversed call edge issue
            // if `A` called `B: Runnable`'s `start`, then there should be edge from `A` to `B`, but it seems reversed now

            collectHappensBefore(dominanceDirectedGraph, stmt, currentInvoked);

            // NOTE: there is some bug with callgraph construction which doesn't link the parent with certain APIs,
            // e.g. Runtime.exec correctly. Example: c577b7e730f955a5f99642e5a8898f64a5b5080d1bf2096804f9992a895ac956.apk
            addCallEdge(method, currentInvoked);

            if ((conditions.isSensitiveMethod(currentInvoked)) || conditions.isDataflowMethod(currentInvoked)) {
                collectLocalDataflowIntoSensitiveAPI(stmt, currentInvoked);
            }
        }
    }

    private void collectLocalDataflowIntoSensitiveAPI(Unit stmt, SootMethod currentInvoked) {
        for (ValueBox use : stmt.getUseBoxes()) {
            if (use.getValue() instanceof IntConstant) {
                String str = String.valueOf(((IntConstant) use.getValue()).value);
                if (dot.libNodeMethods().contains(currentInvoked)) {
                    dot.drawNode(str, NodeType.CONST_INT());
                    dot.drawEdge(str, currentInvoked, EdgeType.DATAFLOW());
                }
            } else if (use.getValue() instanceof StringConstant) {
                if (dot.libNodeMethods().contains(currentInvoked)) {
                    String str = Util.fixedDotStr(((StringConstant) use.getValue()).value);
                    if (str == null || str.length() > 256) {
                        continue;
                    }
                    dot.drawNode(str, NodeType.CONST_STRING());
                    dot.drawEdge(str, currentInvoked, EdgeType.DATAFLOW());
                }
            }
        }
    }

    private void collectHappensBefore(DirectedGraph<Unit> dominanceDirectedGraph, Unit stmt, SootMethod currentInvoked) {
        for (Unit dominator : dominanceDirectedGraph.getPredsOf(stmt)) {
            SootMethod domInvoked = getInvokedMethod(dominator);
            if (domInvoked == null) continue;
            if (dot.libNodeMethods().contains(domInvoked) && dot.libNodeMethods().contains(currentInvoked)
                    && conditions.isSensitiveMethod(domInvoked) && conditions.isSensitiveMethod(currentInvoked)) {
                dot.drawEdge(domInvoked, currentInvoked, EdgeType.DOMINATE());
            }
        }
    }

    /**
     * Dataflow from return value's source methods to the method which returns
     */
    private void collectReturnDataflow(SootMethod method, LocalDefs localDefs, Unit stmt) {
        if (stmt instanceof ReturnStmt) {
            for (ValueBox use : stmt.getUseBoxes()) {
                if (use.getValue() instanceof Local) {
                    Local l = (Local) use.getValue();
                    for (Unit defUnit : localDefs.getDefsOfAt(l, stmt)) {
                        SootMethod defInvoked = getInvokedMethod(defUnit);
                        if (defInvoked == null) continue;
                        dot.drawEdge(defInvoked, method, EdgeType.DATAFLOW());
                    }
                }
            }
        }
    }

    private void collectSensitiveInheritance(SootMethod method) {
        SootClass sensitiveParent = conditions.getSensitiveParentClassWithMethod(method.getDeclaringClass(), method.getSubSignature());
        if (sensitiveParent != null) {
            SootMethod pM = sensitiveParent.getMethod(method.getSubSignature());
            dot.drawNode(method);
            dot.drawNode(pM, NodeType.SENSITIVE_PARENT());
            dot.drawEdge(pM, method, EdgeType.FROM_SENSITIVE_PARENT_TO_SENSITIVE_API());
        }
    }

    private void collectCallGraphRelationships(CallGraph cg) {
        for (Edge e : cg) {
            addCallEdge(e.getSrc().method(), e.getTgt().method());
        }
    }

    private void addCallEdge(SootMethod src, SootMethod tgt) {
        if (Conditions.blacklisted(src)) { return; }
        dot.drawNode(src);
        dot.drawNode(tgt);
        if (!tgt.getDeclaringClass().getName().equals("java.lang.RuntimeException")) {
            dot.drawEdge(src, tgt, EdgeType.CALL());
        }
    }

    private void collectStaticStringFields(Map<SootClass, Map<String, String>> staticStringMap) {
        for (SootClass cls : staticStringMap.keySet()) {
            if (Conditions.blacklisted(cls)) { continue;}
            try {
                dot.drawNode(cls.getMethodByName("<clinit>"));
                for (String fieldName : staticStringMap.get(cls).keySet()) {
                    String value = staticStringMap.get(cls).get(fieldName);
                    if (value.length() > 256) {
                        logger.warn("Skip too long static string {}", value);
                        continue;
                    }
                    String assignment = fieldName + " = " + StringEscapeUtils.escapeJava(value);
                    dot.drawNode(assignment, NodeType.CONST_STRING());
                    dot.drawEdge(cls.getMethodByName("<clinit>"), assignment);
                }
            } catch (RuntimeException e) {
                logger.debug(e.toString());
            }
        }
    }
}
