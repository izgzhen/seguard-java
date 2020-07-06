package edu.washington.cs.seguard

import java.io.{File, Reader, Writer}
import java.nio.charset.StandardCharsets
import java.nio.file.Files

import com.semantic_graph.writer.GexfWriter
import edu.washington.cs.seguard.SeGuardEdgeAttr.SeGuardEdgeAttr
import edu.washington.cs.seguard.SeGuardNodeAttr.SeGuardNodeAttr
import edu.washington.cs.seguard.js.JSFlowGraph
import org.junit.Test

import better.files._

import scala.jdk.CollectionConverters._
import org.junit.Assert._


class JsTest {
  private val record = false

  def compareSetOfStrings(expectedFile: String, actual: List[String]): Unit = {
    if (record) {
      Util.writeLines(expectedFile, actual.asJava)
      return
    }
    val expected: List[String] = Util.readLines(expectedFile).asScala.toList
    val msg = String.format("\nActual: %s\n\nDiff: %s\n\n", actual.mkString("\n"), expected.toSet.diff(actual.toSet).mkString("\n"))
    assertEquals(msg, expected, actual)
  }

  private def transfer(source: Reader, destination: Writer): Unit = {
    val buffer = new Array[Char](1024 * 16)
    var len = source.read(buffer)
    while (len >= 0) {
      destination.write(buffer, 0, len)
      len = source.read(buffer)
    }
  }

  def mergeFiles(output: File, inputfile1: File, inputfile2: File): Unit = {
    try {
      val sourceA = Files.newBufferedReader(inputfile1.toPath)
      val sourceB = Files.newBufferedReader(inputfile2.toPath)
      val destination = Files.newBufferedWriter(output.toPath, StandardCharsets.UTF_8)
      try {
        transfer(sourceA, destination)
        transfer(sourceB, destination)
      } finally {
        if (sourceA != null) sourceA.close()
        if (sourceB != null) sourceB.close()
        if (destination != null) destination.close()
      }
    }
  }

  def testExampleJS(jsPath: String): Set[String] = {
    val g = new GexfWriter[SeGuardNodeAttr, SeGuardEdgeAttr]()
    val cg = JSFlowGraph.addCallGraph(g, jsPath)
    JSFlowGraph.addDataFlowGraph(g, cg)
    val labels = g.getNodes.map(i => g.getNodeLabel(i))
    compareSetOfStrings(jsPath.replace(".js", ".nodes.txt"), labels.toList)
    compareSetOfStrings(jsPath.replace(".js", ".edges.txt"), g.getEdges.map { case (u, v) =>
      val lu = g.getNodeLabel(u)
      val lv = g.getNodeLabel(v)
      lu + "-[" + g.getEdgeAttrs(u, v)(SeGuardEdgeAttr.TYPE) + "]->" + lv
    }.toList)
    g.write(jsPath.replace(".js", ".gexf"))
    labels
  }

  @Test def testExampleJS1(): Unit = {
    testExampleJS("src/test/resources/js/example.js")
  }

  /**
   * See https://github.com/semantic-graph/seguard-java/issues/2 for some related issue
   * FIXME: The current edges list is not perfect since the new object-access-path based node is not connected to other
   *        nodes. It should be able to find their replacements.
   *
   */
  @Test
  def testEventStreamJS(): Unit = {
    val labels = testExampleJS("src/test/resources/eventstream.js")
    assertTrue(labels.contains("process[env][npm_package_description]"))
  }

  @Test
  def testExampleJS2(): Unit = {
    testExampleJS("src/test/resources/js/example2.js")
  }

  private def testJSWithEntrypoints(jsPath: String): Unit = {
    val jsPathFile = new File(jsPath)
    val jsDir = jsPathFile.getParentFile
    val jsName = jsPathFile.getName
    val jsGeneratedDir = jsDir.getParent + "/generated"
    val entrypointsJsPath = jsGeneratedDir + "/" + jsName.replace(".js", ".entrypoints.js")
    val entrypoints = JSFlowGraph.getAllMethods(jsPath)
    compareSetOfStrings(entrypointsJsPath, entrypoints)
    val newJsPath = jsGeneratedDir + "/" + jsName
    mergeFiles(
      new File(newJsPath),
      new File(jsPath),
      new File(entrypointsJsPath))
    testExampleJS(newJsPath)
  }

  @Test
  def testExampleJS3(): Unit = {
    testJSWithEntrypoints("src/test/resources/js/example3.js")
  }

  @Test
  def testConventionalExamples(): Unit = {
    val dir = "src"/"test"/"resources"/"conventional-changelog"/"js"
    for (jsFile <- dir.glob("*.js")) {
      testJSWithEntrypoints(jsFile.toString)
    }
  }
}
