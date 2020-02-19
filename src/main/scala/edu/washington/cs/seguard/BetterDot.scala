package edu.washington.cs.seguard

import java.io.{File, FileWriter}
import java.util

import it.uniroma1.dis.wsngroup.gexf4j.core.data.{AttributeClass, AttributeType}
import it.uniroma1.dis.wsngroup.gexf4j.core.impl.{GexfImpl, StaxGraphWriter}
import it.uniroma1.dis.wsngroup.gexf4j.core.impl.data.AttributeListImpl
import it.uniroma1.dis.wsngroup.gexf4j.core.{Gexf, Mode, EdgeType => GexfEdgeType}
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

sealed abstract class GraphBackend extends Serializable with Product {
    def drawNode(name: String, domain: String, nodeType: NodeType.Value): Unit
    def getNodes: java.util.Set[String]
    def getEdges: java.util.Set[String]
    def getEdgesWithType(parent: BetterDot): java.util.Set[String]
    def addEdge(from: String, to: String, edgeType: EdgeType.Value, parent: BetterDot): Unit
    def write(path: String): Unit
}

object GraphBackend {
    final case class DOT() extends GraphBackend {
        private val dot = new DotGraph("")
        private val edges = new mutable.HashMap[(String, String), DotGraphEdge]
        private val nodes = new mutable.HashMap[String, DotGraphNode]

        override def drawNode(name: String, domain: String, nodeType: NodeType.Value): Unit = {
            val node = dot.drawNode(name)
            if (domain != null) {
                node.setAttribute("domain", domain)
            }
            node.setAttribute("type", nodeType.toString)
            nodes.put(name, node)
        }

        def addEdge(from: String, to: String, edgeType: EdgeType.Value, parent: BetterDot): Unit = {
            val edge = dot.drawEdge(from, to)
            if (edgeType != null) {
                edge.setAttribute("type", edgeType.toString)
                parent.edgeTypes.put((from, to), edgeType)
            }
            edges.put((from, to), edge)
        }

        def getNodes: java.util.Set[String] = {
            nodes.keySet.asJava
        }

        def getEdges: java.util.Set[String] = {
            edges.keySet.map{case (u, v) => u + "->" + v}.asJava
        }

        def getEdgesWithType(parent: BetterDot): java.util.Set[String] = {
            edges.keySet.map{case(u, v) => u + "-[" + parent.edgeTypes((u, v)) + "]->" + v}.asJava
        }

        def write(path: String): Unit = dot.plot(path)
    }

    final case class GEXF() extends GraphBackend {
        private val gexf = new GexfImpl()
        private val graph = gexf.getGraph
        graph.setDefaultEdgeType(GexfEdgeType.DIRECTED).setMode(Mode.STATIC)
        private val nodeAttrs = new AttributeListImpl(AttributeClass.NODE)
        graph.getAttributeLists.add(nodeAttrs)
        private val edgeAttrs = new AttributeListImpl(AttributeClass.EDGE)
        graph.getAttributeLists.add(edgeAttrs)
        private val nodeDomainAttr = nodeAttrs.createAttribute("domain", AttributeType.STRING, "domain")
        private val nodeTypeAttr = nodeAttrs.createAttribute("type", AttributeType.STRING, "type")
        private val edgeTypeAttr = edgeAttrs.createAttribute("type", AttributeType.STRING, "type")

        override def drawNode(name: String, domain: String, nodeType: NodeType.Value): Unit = {
            val node = graph.createNode(name)
            if (domain != null) {
                node.getAttributeValues.addValue(nodeDomainAttr, domain)
            }
            node.getAttributeValues.addValue(nodeTypeAttr, nodeType.toString)
        }

        override def getNodes: util.Set[String] = graph.getNodes.asScala.map(_.getLabel).toSet.asJava

        override def getEdges: util.Set[String] =
            graph.getAllEdges.asScala.map(edge => edge.getSource.getLabel + "->" + edge.getTarget.getLabel).toSet.asJava

        override def getEdgesWithType(parent: BetterDot): util.Set[String] =
            graph.getAllEdges.asScala.map(edge => {
                val u = edge.getSource.getLabel
                val v = edge.getTarget.getLabel
                u + "-[" + parent.edgeTypes((u, v)) + "]->" + v
            }).toSet.asJava

        override def addEdge(from: String, to: String, edgeType: EdgeType.Value, parent: BetterDot): Unit = {
            val edge = graph.getNode(from).connectTo(graph.getNode(to))
            if (edgeType != null) {
                edge.getAttributeValues.addValue(edgeTypeAttr, edgeType.toString)
                parent.edgeTypes.put((from, to), edgeType)
            }
        }

        def write(path: String): Unit = {
            val graphWriter = new StaxGraphWriter()
            val f = new File(path)
            val out = new FileWriter(f, false)
            graphWriter.writeToStream(gexf, out, "UTF-8")
        }
    }
}

class BetterDot(backend: GraphBackend, conditions: Conditions) {
    var libNodeMethods = new mutable.HashSet[SootMethod]
    val nodeTypes = new mutable.HashMap[String, NodeType.Value]
    val edgeTypes = new mutable.HashMap[(String, String), EdgeType.Value]

    def getNodes: java.util.Set[String] = backend.getNodes

    def getEdges: java.util.Set[String] = backend.getEdges

    def getEdgesWithType: java.util.Set[String] = backend.getEdgesWithType(this)

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
        if (getNodes.contains(name)) {
            if (isLargerType(nodeType, nodeTypes.get(name))) {
                nodeTypes.put(name, nodeType)
            }
        }  else {
            if (nodeType != null) {
                nodeTypes.put(name, nodeType)
            }
            backend.drawNode(name, domain, nodeType)
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
        if (!getNodes.contains(from) || !getNodes.contains(to)) {
            throw new RuntimeException("Adding edges without adding nodes first");
        }
        if (!getNodes.contains(from, to)) {
            backend.addEdge(from, to, edgeType, this)
        }
    }

    def write(path: String): Unit = backend.write(path)
}