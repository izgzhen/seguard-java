package edu.washington.cs.seguard

import soot.SootMethod
import soot.util.dot.{DotGraph, DotGraphEdge, DotGraphNode}

import scala.collection.mutable
import scala.jdk.CollectionConverters._

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

class BetterDot(dot : DotGraph, conditions: Conditions) {
    var edges = new mutable.HashMap[(String, String), DotGraphEdge]
    var nodes = new mutable.HashMap[String, DotGraphNode]
    var nodeTypes = new mutable.HashMap[String, NodeType.Value]
    var edgeTypes = new mutable.HashMap[(String, String), EdgeType.Value]
    var libNodeMethods = new mutable.HashSet[SootMethod]

    def getNodes: java.util.Set[String] = {
        nodes.keySet.asJava
    }

    def getEdges: java.util.Set[String] = {
        edges.keySet.map{case (u, v) => u + "->" + v}.asJava
    }

    def getEdgesWithType: java.util.Set[String] = {
        edges.keySet.map{case(u, v) => u + "-[" + edgeTypes((u, v)) + "]->" + v}.asJava
    }

    def drawNode(m: SootMethod) {
        if (conditions.isSensitiveMethod(m)) {
            drawNode(m, NodeType.SENSITIVE_METHOD)
        }
        else {
            drawNode(m, NodeType.METHOD)
        }
    }

    def drawNode(m: SootMethod, nodeType: NodeType.Value) {
        val domain = if (m.getDeclaringClass.isApplicationClass) {
            "application"
        } else {
            libNodeMethods.add(m)
            "library"
        }
        drawNode(m.getSignature, nodeType, domain)
    }

    def drawNode(name: String, nodeType: NodeType.Value) {
        if (name.length > 0) {
            drawNode(name, nodeType, null)
        }
    }

    def drawNode(name: String, nodeType: NodeType.Value, domain: String) {
        assert(name.length > 0)
        nodes.get(name) match {
            case Some(_) =>
                if (isLargerType(nodeType, nodeTypes.get(name))) {
                    nodeTypes.put(name, nodeType)
                }
            case None =>
                val node = dot.drawNode(name)
                if (nodeType != null) {
                    nodeTypes.put(name, nodeType)
                }
                if (domain != null) {
                    node.setAttribute("domain", domain)
                }
                nodes.put(name, node)
        }
    }

    def isLargerType(t1: NodeType.Value, t2: Option[NodeType.Value]) : Boolean = {
        if (t1 == null) {
            return false
        }
        t2 match {
            case None => true
            case Some(t2Val) =>
                t1 != NodeType.METHOD && t2Val == NodeType.METHOD
        }
    }

    def drawEdge(src: SootMethod, tgt: SootMethod, edgeType: EdgeType.Value) {
        drawEdge(src.getSignature, tgt.getSignature, edgeType)
    }

    def drawEdge(strStr: String, tgt: SootMethod, edgeType: EdgeType.Value) {
        drawEdge(strStr, tgt.getSignature, edgeType)
    }

    def drawEdge(src: SootMethod, tgtSig: String) {
        drawEdge(src.getSignature, tgtSig, EdgeType.FROM_CLINIT_TO_ATTRIBUTE)
    }

    def drawEdge(from: String, to: String, edgeType: EdgeType.Value) {
        if (nodes.get(from) == null || nodes.get(to) == null) {
            throw new RuntimeException("Adding edges without adding nodes first");
        }
        edges.get(from, to) match {
            case Some(_) =>
            case None =>
                val edge = dot.drawEdge(from, to)
                if (edgeType != null) {
                    edge.setAttribute("type", edgeType.toString)
                    edgeTypes.put((from, to), edgeType)
                }
                edges.put((from, to), edge)
        }
    }

    def plot(path: String) {
        nodeTypes foreach { case (name, nodeType) => {
                nodes.get(name) match {
                    case None =>
                    case Some(node) => node.setAttribute("type", nodeType.toString)
                }
            }
            dot.plot(path)
        }
    }
}