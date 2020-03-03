package edu.washington.cs.seguard.js

import com.semantic_graph.{EdgeType, NodeId}
import com.semantic_graph.writer.GraphWriter
import edu.washington.cs.seguard.{NodeType, SeGuardEdgeAttr, SeGuardNodeAttr}

/* Created at 3/3/20 by zhen */
object ScalaHelpers {
  def createNodeForGraph[N, E](g: GraphWriter[N, E], label: String, nodeType: NodeType.Value): NodeId = {
    g.createNode(label, Map(SeGuardNodeAttr.TYPE -> nodeType.toString))
  }

  def createNodeForGraph[N, E](g: GraphWriter[N, E], label: String): NodeId = {
    g.createNode(label, Map())
  }

  def addEdgeForGraph[N, E](g: GraphWriter[N, E], u: NodeId, v: NodeId, edgeType: EdgeType.Value): Unit = {
    g.addEdge(u, v, Map(SeGuardEdgeAttr.TYPE -> edgeType.toString))
  }
}
