package edu.washington.cs.seguard

object NodeType extends Enumeration {
    val METHOD,
    EXPR,
    SENSITIVE_PARENT,
    SENSITIVE_METHOD,
    STMT,
    STATIC_STRING,
    LIBRARY,
    merged,
    CONST_STRING,
    CONST_INT,
    CONSTANT,
    entrypoint = Value
}

object EdgeType extends Enumeration {
    val FROM_SENSITIVE_PARENT_TO_SENSITIVE_API,
    CALL,
    METHOD_BODY_CONTAINS,
    DATAFLOW,
    FROM_CLINIT_TO_ATTRIBUTE,
    USE_CONST_STRING,
    DOMINATE,
    DEP = Value
}


object SeGuardNodeAttr extends Enumeration with Serializable {
    type SeGuardNodeAttr = Value
    val DOMAIN = Value("domain")
    val TYPE = Value("type")
    val TAG = Value("tag")
}

object SeGuardEdgeAttr extends Enumeration with Serializable {
    type SeGuardEdgeAttr = Value
    val TYPE = Value("type")
    val TAG = Value("tag")
}
//
//object GraphBackend {
//    final case class DOT[NodeAttr, EdgeAttr]() extends GraphBackend[NodeAttr, EdgeAttr] {
//        private val dot = new DotGraph("")
//        private var nodeCounter = 0
//        private val nodes = mutable.Map[NodeId, DotGraphNode]()
//        private val edges = mutable.Map[(NodeId, NodeId), DotGraphNode]()
//
//        override def createNode(label: String, attrs: Map[NodeAttr, String]): NodeId = {
//            val nodeId = NodeId(nodeCounter.toString)
//            nodeCounter += 1
//            val node = dot.drawNode(nodeId.id)
//            for ((attr, value) <- attrs) {
//                node.setAttribute("label", label)
//                node.setAttribute(attr.toString, value)
//            }
//            nodeId
//        }
//
//        override def addEdge(from: NodeId, to: NodeId, attrs: Map[EdgeAttr, String]): Unit = {
//            if edges.contains((from, to))
//        }
//
//        def write(path: String): Unit = dot.plot(path)
//
//        override def getNodes: Set[NodeId] = nodes.keySet.toSet
//
//        override def getEdges: Set[(NodeId, NodeId)] = edges.keySet.toSet
//
//        override def getEdgeAttrs(srcId: String, tgtId: String): Map[String, String] = ???
//
//    }
//}
//
//class BetterDot(backend: GraphBackend, conditions: Conditions) {
//    var libNodeMethods = new mutable.HashSet[SootMethod]
//    val nodeTypes = new mutable.HashMap[String, NodeType.Value]
//    val edgeTypes = new mutable.HashMap[(String, String), EdgeType.Value]
//
//    def getNodes: java.util.Set[String] = backend.getNodes
//
//    def getEdges: java.util.Set[String] = backend.getEdges
//
//    def getEdgesWithType: java.util.Set[String] = backend.getEdgesWithType(this)
//
//    def drawNode(m: SootMethod): String = {
//        if (conditions.isSensitiveMethod(m)) {
//            drawNode(m, NodeType.SENSITIVE_METHOD)
//        }
//        else {
//            drawNode(m, NodeType.METHOD)
//        }
//    }
//
//    def drawNode(m: SootMethod, nodeType: NodeType.Value): String = {
//        val domain = if (m.getDeclaringClass.isApplicationClass) {
//            "application"
//        } else {
//            libNodeMethods.add(m)
//            "library"
//        }
//        drawNode(m.getSignature, nodeType, domain)
//    }
//
//    def drawNode(name: String, nodeType: NodeType.Value): String = {
//        assert (name.length > 0)
//        drawNode(name, nodeType, null)
//    }
//
//    def drawNode(name: String, nodeType: NodeType.Value, domain: String): String = {
//        assert(name.length > 0)
//        if (getNodes.contains(name)) {
//            if (isLargerType(nodeType, nodeTypes.get(name))) {
//                nodeTypes.put(name, nodeType)
//            }
//            getNodes
//        }  else {
//            if (nodeType != null) {
//                nodeTypes.put(name, nodeType)
//            }
//            backend.drawNode(name, domain, nodeType)
//        }
//    }
//
//    def isLargerType(t1: NodeType.Value, t2: Option[NodeType.Value]) : Boolean = {
//        if (t1 == null) {
//            return false
//        }
//        t2 match {
//            case None => true
//            case Some(t2Val) =>
//                t1 != NodeType.METHOD && t2Val == NodeType.METHOD
//        }
//    }
//
//    def drawEdge(src: SootMethod, tgt: SootMethod, edgeType: EdgeType.Value) {
//        drawEdge(src.getSignature, tgt.getSignature, edgeType)
//    }
//
//    def drawEdge(strStr: String, tgt: SootMethod, edgeType: EdgeType.Value) {
//        drawEdge(strStr, tgt.getSignature, edgeType)
//    }
//
//    def drawEdge(src: SootMethod, tgtSig: String) {
//        drawEdge(src.getSignature, tgtSig, EdgeType.FROM_CLINIT_TO_ATTRIBUTE)
//    }
//
//    def drawEdge(from: String, to: String, edgeType: EdgeType.Value) {
//        if (!getNodes.contains(from)) {
//            throw new RuntimeException("Adding edge without adding node ( " + from + ") first")
//        }
//        if (!getNodes.contains(to)) {
//            throw new RuntimeException("Adding edge without adding node (" + to + ") first")
//        }
//        if (!getNodes.contains(from, to)) {
//            backend.addEdge(from, to, edgeType, this)
//        }
//    }
//
//    def write(path: String): Unit = backend.write(path)
//}