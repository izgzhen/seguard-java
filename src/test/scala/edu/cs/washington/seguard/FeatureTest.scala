package edu.cs.washington.seguard

import edu.washington.cs.seguard.{BetterDot, Conditions, NodeType, SootOptionManager, Util}
import edu.washington.cs.seguard.Util.Lang
import edu.washington.cs.seguard.core.FlowGraph
import org.junit.Test
import soot.Scene
import soot.util.dot.DotGraph
import org.junit.Assert._

class FeatureTest {
  def testNode(dot: BetterDot, lang: Util.Lang, nodeType: NodeType.Value, nodeName: String): Unit = {
    assertTrue(dot.nodes.toString(), dot.nodes.contains(nodeName))
    assertEquals(dot.nodeTypes.get(nodeName).toString, dot.nodeTypes.get(nodeName), Some(nodeType))
  }

  def runAnalysisOnTestResource(): BetterDot = {
    val conditions = new Conditions("SourcesAndSinks.txt")
    val dot = new BetterDot(new DotGraph(""), conditions)
    val flowGraph = new FlowGraph(conditions, null, dot)
    SootOptionManager.Manager().buildOptionTest()
    Scene.v.loadNecessaryClasses()
    flowGraph.Main()
    assertEquals(dot.nodes.toString(), dot.nodes.size, 11)
    dot
  }

  @Test def testFeatures(): Unit = {
    val dot = runAnalysisOnTestResource()
    testNode(dot, Lang.JAVA, NodeType.METHOD, "<Test: void <clinit>()>")
  }
}
