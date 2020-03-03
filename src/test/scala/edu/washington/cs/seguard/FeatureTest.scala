package edu.washington.cs.seguard

import com.semantic_graph.writer.{GexfWriter, GraphWriter}
import edu.washington.cs.seguard.SeGuardEdgeAttr.SeGuardEdgeAttr
import edu.washington.cs.seguard.SeGuardNodeAttr.SeGuardNodeAttr
import edu.washington.cs.seguard.Util.Lang
import edu.washington.cs.seguard.core.FlowGraph
import org.junit.Test
import soot.Scene
import org.junit.Assert._

class FeatureTest {
  def testNode(g: GraphWriter[SeGuardNodeAttr, SeGuardEdgeAttr],
               lang: Util.Lang, nodeType: NodeType.Value, nodeName: String): Unit = {
    val nodeTypes = g.getNodes.map(i => (g.getNodeLabel(i), g.getNodeAttrs(i)(SeGuardNodeAttr.TYPE))).toMap
    assertTrue(g.getNodes.toString, nodeTypes.contains(nodeName))
    assertEquals(nodeName, nodeTypes.get(nodeName), Some(nodeType))
  }

  def runAnalysisOnTestResource(): GraphWriter[SeGuardNodeAttr, SeGuardEdgeAttr] = {
    val config = Config.load("src/test/resources/config.yaml")
    val conditions = new Conditions("SourcesAndSinks.txt", config)
    val g = new GexfWriter[SeGuardNodeAttr, SeGuardEdgeAttr]()
    val flowGraph = new FlowGraph(conditions, null, g, config)
    SootOptionManager.Manager().buildOptionTest()
    Scene.v.loadNecessaryClasses()
    flowGraph.Main()
    assertEquals(g.getNodes.toString, 11, g.getNodes.size)
    g
  }

  @Test def testFeatures(): Unit = {
    val g = runAnalysisOnTestResource()
    testNode(g, Lang.JAVA, NodeType.METHOD, "<Test: void <clinit>()>")
  }
}
