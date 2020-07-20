/*
  Program -> Program Facts -> Concrete Flow Graph
  TODO: decouple PF and ConcFG
  @author: Zhen Zhang
 */

package edu.washington.cs.seguard.apk_core

import java.io.PrintWriter
import java.util.Collections

import com.semantic_graph.NodeId
import com.semantic_graph.writer.GraphWriter
import edu.washington.cs.seguard.{Abstraction, Conditions, Config, EdgeType, NodeType, SeGuardEdgeAttr, SeGuardNodeAttr, SootOptionManager, SootUtil, Util}
import edu.washington.cs.seguard.SeGuardEdgeAttr.SeGuardEdgeAttr
import edu.washington.cs.seguard.SeGuardNodeAttr.SeGuardNodeAttr
import edu.washington.cs.seguard.core.IFDSDataFlowTransformer
import edu.washington.cs.seguard.pe.AliasRewriter
import edu.washington.cs.seguard.util.StatManager
import org.apache.commons.lang3.StringEscapeUtils
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import soot.jimple.{InstanceInvokeExpr, IntConstant, ReturnStmt, Stmt, StringConstant}
import soot.jimple.infoflow.entryPointCreators.DefaultEntryPointCreator
import soot.jimple.toolkits.callgraph.{CallGraph, Edge}
import soot.toolkits.graph.{BriefUnitGraph, DirectedGraph, MHGDominatorsFinder}
import soot.toolkits.scalar.{LocalDefs, SimpleLiveLocals, SmartLocalDefs}
import soot.{Body, Local, PackManager, RefType, Scene, SceneTransformer, SootClass, SootMethod, Transform}

import scala.collection.mutable
import scala.jdk.CollectionConverters._

class FlowGraph(conditions: Conditions, statManager: StatManager,
                graphWriter: GraphWriter[SeGuardNodeAttr, SeGuardEdgeAttr],  config: Config) {
    private val logger: Logger = LoggerFactory.getLogger("FlowGraph")

    private val libraryClasses = mutable.Set[SootClass]()
    private def isLibraryClass(c: SootClass) = libraryClasses.contains(c)

    /**
     * Construct flow-graph
     */
    def Main(): Unit = {
        Scene.v().loadNecessaryClasses()

        for (c <- Scene.v().getClasses.asScala) {
            if (config.getLibraryPrefixes.asScala.exists(prefix => c.getName.startsWith(prefix))) {
                libraryClasses.add(c)
            }
        }

        // FIXME: port StatManager to Scala
        //        if (statManager != null) {
        //            statManager.put(BASIC_CLASSES, Scene.v().getBasicClasses().size());
        //            statManager.put(CLASSES, Scene.v().getClasses().size());
        //            statManager.put(LIBRARY_CLASSES, Scene.v().getLibraryClasses().size());
        //            statManager.put(PHANTON_CLASSES, Scene.v().getPhantomClasses().size());
        //        }

        val staticStringMap = setEntrypointsAndGetStaticStringMap()
        addStaticStringFactsToGraph(staticStringMap)

        val transformer = new IFDSDataFlowTransformer(conditions, config)

        // Resolve some aliasing by instrumentation to ease the next phase of analysis
        PackManager.v().getPack("wjtp").add(new Transform("wjtp.aliasrewriter", new AliasRewriter(conditions)));
        // Run our IFDS/IDE data-flow analysis (DFA)
        PackManager.v().getPack("wjtp").add(new Transform("wjtp.herosifds", transformer));
        // Collect results into the flow-graph by first analyzing the data-flow facts from previous FDA and also
        // static facts from call-graph
        PackManager.v().getPack("wjtp").add(new Transform("wjtp.myTransform", new SceneTransformer() {
            override def internalTransform(phaseName: String, options: java.util.Map[String, String]) {
                addAllDataFlowFactsToGraph(transformer)
                addOtherFactsToGraph()
            }
        }));
        logger.info("Run Soot packs...")
        SootOptionManager.Manager().sootRunPacks()
    }

    /**
     * Collect some data-flow facts into flow-graph
     */
    private def addAllDataFlowFactsToGraph(transformer: IFDSDataFlowTransformer): Unit = {
        for (cls <- Scene.v().getClasses.asScala) {
            for (m <- cls.getMethods.asScala) {
                if (!(cls.isJavaLibraryClass || !m.hasActiveBody) && !(conditions.blacklisted(m))) {
                    val b = m.getActiveBody
                    for (u <- b.getUnits.asScala) {
                        val s = u.asInstanceOf[Stmt]
                        if (s.containsInvokeExpr) {
                            val invoked = s.getInvokeExpr.getMethod
                            if (conditions.isSensitiveMethod(invoked)) {
                                // For each value used by the sensitive method invocation
                                for (vbox <- u.getUseBoxes.asScala) {
                                    for (p <- transformer.solver.ifdsResultsAt(u).asScala) {
                                        if (vbox.getValue.equals(p.getO1)) {
                                            // Check what is the abstract value for the parameters and add data-flow facts
                                            for (abstraction <- p.getO2.asScala) {
                                                addDataFlowFactsToGraph(abstraction, graphWriter, invoked)
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

    /**
     * Go through app classes and Set entry-points of analysis
     * @return collected map of each class's static string attributes (hame -> value)
     */
    private def setEntrypointsAndGetStaticStringMap(): Map[SootClass, Map[String, String]] = {
        val staticStringMap = mutable.TreeMap[SootClass, Map[String, String]]()(Ordering.by(_.toString))

        // Collecting interesting methods and static attribute strings by iterating all classes
        val appMethods = mutable.TreeSet[SootMethod]()(Ordering.by(_.toString))
        for (appCls <- Scene.v().getApplicationClasses.asScala) {
            if (appCls.isConcrete) {
                Scene.v().forceResolve(appCls.getName, SootClass.BODIES)
                staticStringMap.put(appCls, Util.getStaticStrings(appCls).asScala.toMap)
                for (appMethod <- appCls.getMethods.asScala) {
                    try {
                        appMethod.retrieveActiveBody()
                        val b = appMethod.getActiveBody
                        if (b != null) {
                            appMethods.add(appMethod)
                        }
                    } catch {
                        case _: RuntimeException =>
                    }
                }
            }
        }

        // FIXME: Reuse this code when statManager is ported
        //        if (statManager != null) {
        //            statManager.put(MS_NUM, ms.size());
        //            statManager.put(ORIGINAL_NUM_ENTRYPOINTS, Scene.v().getEntryPoints().size());
        //        }

        val entryPoints = mutable.TreeSet[String]()
        // for each concrete application class's method with body
        for (m <- appMethods) {
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

        // FIXME: Reuse this code when statManager is ported
        //        if (statManager != null) {
        //            statManager.put(NEW_NUM_ENTRYPOINTS, entryPoints.size());
        //        }

        val entryPointCreator = new DefaultEntryPointCreator(entryPoints.asJavaCollection)
        val dummyMain = entryPointCreator.createDummyMain()

        Scene.v().setEntryPoints(Collections.singletonList(dummyMain))
        staticStringMap.toMap
    }

    private val methodAndReachables = mutable.Map[SootMethod, mutable.Set[SootMethod]]()
    private var methodTargets: Map[SootMethod, Set[SootMethod]] = _

    /**
     * Patch the call graph with silly missing edges
     * FIXME: Why are they missing?
     */
    private def patchCallGraph(): Unit = {
        for (c <- Scene.v().getApplicationClasses.asScala) {
            if (!isLibraryClass(c)) {
                for (m <- c.getMethods.asScala) {
                    try {
                        m.retrieveActiveBody()
                    } catch {
                        case _: RuntimeException =>
                    }
                    if (m.isConcrete && m.hasActiveBody) {
                        for (unit <- m.getActiveBody.getUnits.asScala) {
                            val stmt = unit.asInstanceOf[Stmt]
                            if (stmt.containsInvokeExpr()) {
                                val invokedTarget = SootUtil.getMethodUnsafe(stmt.getInvokeExpr)
                                if (invokedTarget != null) {
                                    var edge: Edge = null
                                    try {
                                        edge = Scene.v().getCallGraph.findEdge(stmt, invokedTarget)
                                    } catch {
                                        case _: Exception =>
                                    }
                                    if (edge == null) {
                                        Scene.v().getCallGraph.addEdge(new Edge(m, stmt, invokedTarget))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }


    def reachableMethods(m: SootMethod): Set[SootMethod] = {
        methodAndReachables.get(m) match {
            case Some(s) => return s.toSet
            case None =>
        }
        val reachables = mutable.Set[SootMethod](m)
        methodAndReachables.put(m, reachables)
        val worklist = mutable.Queue[SootMethod]()
        worklist.addOne(m)
        while (worklist.nonEmpty) {
            val source = worklist.dequeue()
            methodTargets.get(source) match {
                case Some(targets) =>
                    for (target <- targets) {
                        if (!reachables.contains(target)) {
                            reachables.add(target)
                            worklist.addOne(target)
                        }
                    }
                case None =>
            }
        }
        reachables.toSet
    }

    def addCallFactsFromReachables(method: SootMethod): Unit = {
        for (reached <- reachableMethods(method)) {
            if (conditions.isSensitiveMethod(reached) || conditions.isDataflowMethod(reached)) {
                addCallEdge(method, reached)
            }
        }
    }

    /**
     * FIXME: Non-det
     */
    private def addOtherFactsToGraph(): Unit = {
        patchCallGraph()

        val cg = Scene.v().getCallGraph

        methodTargets = Scene.v().getCallGraph.sourceMethods().asScala.map(src => (
          src.method(), Scene.v().getCallGraph.edgesOutOf(src).asScala.map(_.getTgt.method()).toSet)).toMap

        // FIXME: Reuse this code when statManager is ported
        //        if (statManager != null) {
        //            statManager.put(CG_SIZE, cg.size());
        //        }

        if (config.isDebug) {
            val printWriter = new PrintWriter(config.getCallGraphDumpPath)
            printWriter.write(cg.toString)
            printWriter.close()
        }

        // FIXME: this is quite surprising...can we generate reachability info directly?
//        addCallGraphRelationshipFactsToGraph(cg)

        // Collect fact around each method
        val classes = Scene.v().getClasses.asScala.toList
        for (cls <- classes) {
            if (cls.isApplicationClass) {
                for (method <- cls.getMethods.asScala) {
                    if (!method.isJavaLibraryMethod) {
                        if (Constants.isBackgroundContextAPI(method)) {
                            addCallFactsFromReachables(method)
                        }

                        if (!conditions.blacklisted(method)) {
                            collectSensitiveInheritance(method)

                            try {
                                method.retrieveActiveBody()
                                val b = method.getActiveBody
                                if (b != null) {
                                    addIntraprocFactsToGraph(method, b)
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
        }
    }

    private def addIntraprocFactsToGraph(method: SootMethod, b: Body): Unit = {
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

                addHappensBeforeFactToGraph(dominanceDirectedGraph, stmt, currentInvoked)

                // NOTE: there is some bug with callgraph construction which doesn't link the parent with certain APIs,
                // e.g. Runtime.exec correctly. Example: c577b7e730f955a5f99642e5a8898f64a5b5080d1bf2096804f9992a895ac956.apk
//                addCallEdge(method, currentInvoked)

                if ((conditions.isSensitiveMethod(currentInvoked)) || conditions.isDataflowMethod(currentInvoked)) {
                    addLocalDataflowIntoSensitiveAPIFactToGraph(stmt, currentInvoked)
                }
            }
        }
    }

    private val libNodeMethods = mutable.Set[SootMethod]()

    private def addLocalDataflowIntoSensitiveAPIFactToGraph(stmt: soot.Unit, currentInvoked: SootMethod): Unit = {
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

    private def addHappensBeforeFactToGraph(dominanceDirectedGraph: DirectedGraph[soot.Unit],
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

    private def addCallGraphRelationshipFactsToGraph(cg: CallGraph): Unit = {
        for (e <- cg.asScala) {
            addCallEdge(e.getSrc.method(), e.getTgt.method())
        }
    }

    /**
     * Add call edge if source node is not blacklisted
     * @param src
     * @param tgt
     */
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
        graphWriter.createNode(
            method.getDeclaringClass.getName + "." + method.getName,
            Map(SeGuardNodeAttr.TYPE -> nodeType.toString,
                SeGuardNodeAttr.TAG -> tag))
    }

    /**
     * Add static string facts to the abstract graph
     */
    private def addStaticStringFactsToGraph(staticStringMap: Map[SootClass, Map[String, String]]): Unit = {
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
                            val v = graphWriter.createNode(assignment, Map(SeGuardNodeAttr.TYPE -> NodeType.STMT.toString))
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

    def addDataFlowFactsToGraph(abstraction : Abstraction, g : GraphWriter[SeGuardNodeAttr, SeGuardEdgeAttr],
                                invoked: SootMethod): Unit = {
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
                // Value returned from source method (u) is consumed by sink method (v)
                val u = createMethodNode(method)
                val v = createMethodNode(invoked)
                g.addEdge(u, v, Map(SeGuardEdgeAttr.TYPE -> EdgeType.DATAFLOW.toString))
        }
    }
}
