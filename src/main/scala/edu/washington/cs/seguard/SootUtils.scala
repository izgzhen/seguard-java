package edu.washington.cs.seguard

import com.semantic_graph.NodeId
import com.semantic_graph.writer.GraphWriter
import edu.washington.cs.seguard.SeGuardEdgeAttr.SeGuardEdgeAttr
import edu.washington.cs.seguard.SeGuardNodeAttr.SeGuardNodeAttr

object SootUtils {
  def processDataFlowFacts(abstraction : Abstraction, dot : GraphWriter[SeGuardNodeAttr, SeGuardEdgeAttr],
                           invoked: NodeId): Unit = {
    abstraction match {
      case Abstraction.StringConstant(s) =>
        val str = Util.fixedDotStr(s)
        if (str == null || str.length > 256) return
        val u = dot.createNode(str, Map(SeGuardNodeAttr.TYPE -> NodeType.CONST_STRING.toString))
        dot.addEdge(u, invoked, Map(SeGuardEdgeAttr.TYPE -> EdgeType.DATAFLOW.toString))
      case Abstraction.IntegerConstant(integer) =>
        val str = String.valueOf(integer)
        val u = dot.createNode(str, Map(SeGuardNodeAttr.TYPE -> NodeType.CONST_INT.toString))
        dot.addEdge(u, invoked, Map(SeGuardEdgeAttr.TYPE -> EdgeType.DATAFLOW.toString))
      case Abstraction.MethodConstant(method) =>
        val u = dot.createNode(method.getSignature, Map())
        dot.addEdge(u, invoked, Map(SeGuardEdgeAttr.TYPE -> EdgeType.DATAFLOW.toString))
    }
  }
}
