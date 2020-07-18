/*
  Program -> Program Facts -> Concrete Flow Graph
  TODO: decouple PF and ConcFG
  @author: Zhen Zhang
 */

package edu.washington.cs.seguard.java_core

import java.io.PrintWriter
import java.util.Collections

import com.semantic_graph.NodeId
import com.semantic_graph.writer.GraphWriter
import edu.washington.cs.seguard.{Abstraction, Conditions, Config, EdgeType, NodeType, SeGuardEdgeAttr, SeGuardNodeAttr, SootOptionManager, Util}
import edu.washington.cs.seguard.SeGuardEdgeAttr.SeGuardEdgeAttr
import edu.washington.cs.seguard.SeGuardNodeAttr.SeGuardNodeAttr
import edu.washington.cs.seguard.core.IFDSDataFlowTransformer
import edu.washington.cs.seguard.pe.AliasRewriter
import edu.washington.cs.seguard.util.StatManager
import org.apache.commons.lang3.StringEscapeUtils
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import soot.jimple.{IntConstant, ReturnStmt, Stmt, StringConstant}
import soot.jimple.infoflow.entryPointCreators.DefaultEntryPointCreator
import soot.jimple.toolkits.callgraph.CallGraph
import soot.toolkits.graph.{BriefUnitGraph, DirectedGraph, MHGDominatorsFinder}
import soot.toolkits.scalar.{LocalDefs, SimpleLiveLocals, SmartLocalDefs}
import soot.{Body, Local, PackManager, Scene, SceneTransformer, SootClass, SootMethod, Transform}

import scala.collection.mutable
import scala.jdk.CollectionConverters._

class FlowGraph(conditions: Conditions, statManager: StatManager,
                graphWriter: GraphWriter[SeGuardNodeAttr, SeGuardEdgeAttr],  config: Config) {
    private val logger: Logger = LoggerFactory.getLogger("FlowGraph")

    /**
     * Construct flow-graph
     */
    def Main(): Unit = {
        Scene.v().loadNecessaryClasses()

        // FIXME: port StatManager to Scala
        //        if (statManager != null) {
        //            statManager.put(BASIC_CLASSES, Scene.v().getBasicClasses().size());
        //            statManager.put(CLASSES, Scene.v().getClasses().size());
        //            statManager.put(LIBRARY_CLASSES, Scene.v().getLibraryClasses().size());
        //            statManager.put(PHANTON_CLASSES, Scene.v().getPhantomClasses().size());
        //        }

        val staticStringMap = setEntrypointsAndGetStaticStringMap()
        collectStaticStringFields(staticStringMap)

        val transformer = new IFDSDataFlowTransformer(conditions, config)

        // Resolve some aliasing by instrumentation to ease the next phase of analysis
        PackManager.v().getPack("wjtp").add(new Transform("wjtp.aliasrewriter", new AliasRewriter(conditions)));
        // Run our IFDS/IDE data-flow analysis (DFA)
        PackManager.v().getPack("wjtp").add(new Transform("wjtp.herosifds", transformer));
        // Collect results into the flow-graph by first analyzing the data-flow facts from previous FDA and also
        // static facts from call-graph
        PackManager.v().getPack("wjtp").add(new Transform("wjtp.myTransform", new SceneTransformer() {
            override def internalTransform(phaseName: String, options: java.util.Map[String, String]) {
                // Collect some data-flow facts into flow-graph
                collectDataflowFacts(transformer)

                // Collect other facts into the flow-graph (dot)
                // FIXME: Non-det
                processOtherFacts()
            }
        }));
        logger.info("Run Soot packs...")
        SootOptionManager.Manager().sootRunPacks()
    }

    private def collectDataflowFacts(transformer: IFDSDataFlowTransformer): Unit = {
        for (cls <- Scene.v().getClasses.asScala) {
            for (m <- cls.getMethods.asScala) {
                if (!(cls.isJavaLibraryClass || !m.hasActiveBody) && !(conditions.blacklisted(m))) {
                    val b = m.getActiveBody
                    for (u <- b.getUnits.asScala) {
                        val s = u.asInstanceOf[Stmt]
                        if (s.containsInvokeExpr) {
                            val invoked = s.getInvokeExpr.getMethod
                            if (conditions.isSensitiveMethod(invoked)) {
                                for (vbox <- u.getUseBoxes.asScala) {
                                    for (p <- transformer.solver.ifdsResultsAt(u).asScala) {
                                        if (vbox.getValue.equals(p.getO1)) {
                                            for (abstraction <- p.getO2.asScala) {
                                                processDataFlowFacts(abstraction, graphWriter, invoked)
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
    }

    private def setEntrypointsAndGetStaticStringMap(): Map[SootClass, Map[String, String]] = {
        val staticStringMap = mutable.TreeMap[SootClass, Map[String, String]]()(Ordering.by(_.toString))

        // Collecting interesting methods and static attribute strings by iterating all classes
        val ms = mutable.TreeSet[SootMethod]()(Ordering.by(_.toString))
        for (appCls <- Scene.v().getApplicationClasses.asScala) {
            if (appCls.isConcrete) {
                Scene.v().forceResolve(appCls.getName, SootClass.BODIES)
                staticStringMap.put(appCls, Util.getStaticStrings(appCls).asScala.toMap)
                for (appMethod <- appCls.getMethods.asScala) {
                    try {
                        appMethod.retrieveActiveBody()
                        val b = appMethod.getActiveBody
                        if (b != null) {
                            ms.add(appMethod)
                        }
                    } catch {
                        case _: RuntimeException =>
                    }
                }
            }
        }

        //        if (statManager != null) {
        //            statManager.put(MS_NUM, ms.size());
        //            statManager.put(ORIGINAL_NUM_ENTRYPOINTS, Scene.v().getEntryPoints().size());
        //        }
        val entryPoints = mutable.TreeSet[String]()
        // for each concrete application class's method with body
        for (m <- ms) {
            // if the application class inherits some library class, print the inherited library class
            // and count such type of application classes. we might consider them as priority
            val cls = m.getDeclaringClass
            if (!(conditions.blacklisted(cls) || conditions.blacklisted(m))) {
                if (cls.hasSuperclass || cls.getInterfaceCount > 0) {
                    if (conditions.isSensitiveEntrypointParent(cls.getSuperclass)) {
                        entryPoints.add(m.getSignature)
                    } else {
                        for (iface <- cls.getInterfaces.asScala) {
                            if (conditions.isSensitiveEntrypointParent(iface)) {
                                entryPoints.add(m.getSignature)
                            }
                        }
                    }
                }
            }
        }
        //
        //        if (statManager != null) {
        //            statManager.put(NEW_NUM_ENTRYPOINTS, entryPoints.size());
        //        }

        val entryPointCreator = new DefaultEntryPointCreator(entryPoints.asJavaCollection)
        val dummyMain = entryPointCreator.createDummyMain()

        Scene.v().setEntryPoints(Collections.singletonList(dummyMain))
        staticStringMap.toMap
    }

    private def processOtherFacts(): Unit = {
        val cg = Scene.v().getCallGraph
        //        if (statManager != null) {
        //            statManager.put(CG_SIZE, cg.size());
        //        }
        if (config.isDebug) {
            val printWriter = new PrintWriter(config.getCallGraphDumpPath)
            printWriter.write(cg.toString)
            printWriter.close()
        }

        collectCallGraphRelationships(cg)

        // Collect fact around each method
        for (cls <- Scene.v().getClasses.asScala) {
            if (cls.isApplicationClass) {
                for (method <- cls.getMethods.asScala) {
                    if (!method.isJavaLibraryMethod && !conditions.blacklisted(method))
                        try {
                            method.retrieveActiveBody()
                            collectSensitiveInheritance(method)

                            val b = method.getActiveBody
                            if (b != null) {
                                collectIntraprocFacts(method, b)
                            } else {
                                logger.warn("Failed to getActiveBody of {}", method.getSignature)
                            }
                        } catch {
                            case _: RuntimeException =>
                        }
                }
            }
        }
    }

    private def collectIntraprocFacts(method: SootMethod, b: Body): Unit = {
        val unitGraph = new BriefUnitGraph(b)

        // API happens-before
        val dominanceDirectedGraph = new MHGDominatorsFinder[soot.Unit](unitGraph).getGraph

        // Local definitions
        val localDefs = new SmartLocalDefs(unitGraph, new SimpleLiveLocals(unitGraph))

        for (stmt <- unitGraph.asScala) {
            val currentInvoked = getInvokedMethod(stmt)
            if (currentInvoked == null) {
                collectReturnDataflow(method, localDefs, stmt)
            } else {
                // TODO: fix the reversed call edge issue
                // if `A` called `B: Runnable`'s `start`, then there should be edge from `A` to `B`, but it seems reversed now

                collectHappensBefore(dominanceDirectedGraph, stmt, currentInvoked)

                // NOTE: there is some bug with callgraph construction which doesn't link the parent with certain APIs,
                // e.g. Runtime.exec correctly. Example: c577b7e730f955a5f99642e5a8898f64a5b5080d1bf2096804f9992a895ac956.apk
                addCallEdge(method, currentInvoked)

                if ((conditions.isSensitiveMethod(currentInvoked)) || conditions.isDataflowMethod(currentInvoked)) {
                    collectLocalDataflowIntoSensitiveAPI(stmt, currentInvoked)
                }
            }
        }
    }

    private val libNodeMethods = mutable.Set[SootMethod]()

    private def collectLocalDataflowIntoSensitiveAPI(stmt: soot.Unit, currentInvoked: SootMethod): Unit = {
        for (use <- stmt.getUseBoxes.asScala) {
            val v = createMethodNode(currentInvoked)
            use.getValue match {
                case intConstant: IntConstant =>
                    val str = String.valueOf(intConstant.value)
                    if (libNodeMethods.contains(currentInvoked)) {
                        val u = graphWriter.createNode(str, Map(SeGuardNodeAttr.TYPE -> NodeType.CONST_INT.toString))
                        graphWriter.addEdge(u, v, Map(SeGuardEdgeAttr.TYPE -> EdgeType.DATAFLOW.toString))
                    }
                case stringConstant: StringConstant =>
                    val str = Util.fixedDotStr(stringConstant.value)
                    if (libNodeMethods.contains(currentInvoked) && !(str == null || str.length() > 256)) {
                        val u = graphWriter.createNode(str, Map(SeGuardNodeAttr.TYPE -> NodeType.CONST_STRING.toString))
                        graphWriter.addEdge(u, v, Map(SeGuardEdgeAttr.TYPE -> EdgeType.DATAFLOW.toString))
                    }
                case _ =>
            }
        }
    }

    private def collectHappensBefore(dominanceDirectedGraph: DirectedGraph[soot.Unit],
                                     stmt: soot.Unit, currentInvoked: SootMethod) {
        for (dominator <- dominanceDirectedGraph.getPredsOf(stmt).asScala) {
            val domInvoked = getInvokedMethod(dominator)
            if (domInvoked != null) {
                if (libNodeMethods.contains(domInvoked) && libNodeMethods.contains(currentInvoked)
                  && conditions.isSensitiveMethod(domInvoked) && conditions.isSensitiveMethod(currentInvoked)) {
                    val u = createMethodNode(domInvoked)
                    val v = createMethodNode(currentInvoked)
                    graphWriter.addEdge(u, v, Map(SeGuardEdgeAttr.TYPE -> EdgeType.DOMINATE.toString))
                }
            }
        }
    }

    /**
     * Dataflow from return value's source methods to the method which returns
     */
    private def collectReturnDataflow(method: SootMethod, localDefs: LocalDefs, stmt: soot.Unit): Unit = {
        if (stmt.isInstanceOf[ReturnStmt]) {
            for (use <- stmt.getUseBoxes.asScala) {
                use.getValue match {
                    case l: Local =>
                        for (defUnit <- localDefs.getDefsOfAt(l, stmt).asScala) {
                            val defInvoked = getInvokedMethod(defUnit)
                            if (defInvoked != null) {
                                val u = createMethodNode(defInvoked)
                                val v = createMethodNode(method)
                                graphWriter.addEdge(u, v, Map(SeGuardEdgeAttr.TYPE -> EdgeType.DOMINATE.toString))
                            }
                        }
                    case _ =>
                }
            }
        }
    }

    private def collectSensitiveInheritance(method: SootMethod): Unit = {
        val sensitiveParent = conditions.getSensitiveParentClassWithMethod(method.getDeclaringClass, method.getSubSignature)
        if (sensitiveParent != null) {
            val pM = sensitiveParent.getMethod(method.getSubSignature)
            val u = createMethodNode(pM, nodeTypeOpt = Some(NodeType.SENSITIVE_PARENT))
            val v = createMethodNode(method)
            graphWriter.addEdge(u, v,  Map(SeGuardEdgeAttr.TYPE -> EdgeType.FROM_SENSITIVE_PARENT_TO_SENSITIVE_API.toString))
        }
    }

    private def collectCallGraphRelationships(cg: CallGraph): Unit = {
        for (e <- cg.asScala) {
            addCallEdge(e.getSrc.method(), e.getTgt.method())
        }
    }

    private def addCallEdge(src: SootMethod, tgt: SootMethod): Unit = {
        if (!conditions.blacklisted(src)) {
            val u = createMethodNode(src)
            val v = createMethodNode(tgt)
            if (!tgt.getDeclaringClass.getName.equals("java.lang.RuntimeException")) {
                graphWriter.addEdge(u, v, Map(SeGuardEdgeAttr.TYPE -> EdgeType.CALL.toString))
            }
        }
    }

    private def createMethodNode(method: SootMethod, nodeTypeOpt: Option[NodeType.Value] = None): NodeId = {
        val tag = if (method.getDeclaringClass.isApplicationClass) {
            "application"
        } else {
            libNodeMethods.add(method)
            "library"
        }
        val nodeType = nodeTypeOpt match {
            case Some(t) => t
            case None => if (conditions.isSensitiveMethod(method)) {
                NodeType.SENSITIVE_METHOD
            } else {
                NodeType.METHOD
            }
        }
        graphWriter.createNode(method.getName,
            Map(SeGuardNodeAttr.TYPE -> nodeType.toString,
                SeGuardNodeAttr.TAG -> tag))
    }

    private def collectStaticStringFields(staticStringMap: Map[SootClass, Map[String, String]]): Unit = {
        for (cls <- staticStringMap.keySet) {
            if (!conditions.blacklisted(cls)) {
                try {
                    val u = createMethodNode(cls.getMethodByName("<clinit>"))
                    for (fieldName <- staticStringMap(cls).keySet)  {
                        val value = staticStringMap(cls)(fieldName)
                        if (value.length > 256) {
                            logger.warn("Skip too long static string {}", value)
                        } else {
                            val assignment = fieldName + " = " + StringEscapeUtils.escapeJava(value)
                            val v = graphWriter.createNode(assignment, Map(SeGuardNodeAttr.TYPE -> NodeType.CONST_STRING.toString))
                            graphWriter.addEdge(v, u, Map(SeGuardEdgeAttr.TYPE -> EdgeType.DATAFLOW.toString))
                        }
                    }
                } catch {
                    case e: RuntimeException => logger.debug(e.toString)
                }
            }
        }
    }


    def getInvokedMethod(u: soot.Unit): SootMethod = {
        val s = u.asInstanceOf[Stmt]
        if (s.containsInvokeExpr()) {
            val e = s.getInvokeExpr
            return e.getMethod
        }
        null
    }

    def processDataFlowFacts(abstraction : Abstraction, g : GraphWriter[SeGuardNodeAttr, SeGuardEdgeAttr], invoked: SootMethod): Unit = {
        abstraction match {
            case Abstraction.StringConstant(s) =>
                val str = Util.fixedDotStr(s)
                if (str == null || str.length > 256) return
                val u = g.createNode(str, Map(SeGuardNodeAttr.TYPE -> NodeType.CONST_STRING.toString))
                val v = createMethodNode(invoked)
                g.addEdge(u, v, Map(SeGuardEdgeAttr.TYPE -> EdgeType.DATAFLOW.toString))
            case Abstraction.IntegerConstant(integer) =>
                val str = String.valueOf(integer)
                val u = g.createNode(str, Map(SeGuardNodeAttr.TYPE -> NodeType.CONST_INT.toString))
                val v = createMethodNode(invoked)
                g.addEdge(u, v, Map(SeGuardEdgeAttr.TYPE -> EdgeType.DATAFLOW.toString))
            case Abstraction.MethodConstant(method) =>
                val u = createMethodNode(method)
                val v = createMethodNode(invoked)
                g.addEdge(u, v, Map(SeGuardEdgeAttr.TYPE -> EdgeType.DATAFLOW.toString))
        }
    }
}
