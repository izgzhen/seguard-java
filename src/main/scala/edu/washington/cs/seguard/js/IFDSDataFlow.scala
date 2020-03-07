package edu.washington.cs.seguard.js

import com.ibm.wala.cast.ir.ssa.{AstGlobalRead, AstGlobalWrite, AstLexicalWrite, EachElementGetInstruction}
import com.ibm.wala.cast.js.ssa._
import com.ibm.wala.dataflow.IFDS._
import com.ibm.wala.ipa.callgraph.CGNode
import com.ibm.wala.ipa.cfg.BasicBlockInContext
import com.ibm.wala.ipa.cfg.ExplodedInterproceduralCFG
import com.ibm.wala.ssa._
import com.ibm.wala.ssa.analysis.IExplodedBasicBlock
import com.ibm.wala.util.CancelException
import com.ibm.wala.util.collections.Pair
import com.ibm.wala.util.intset.MutableMapping
import com.ibm.wala.util.intset.MutableSparseIntSet
import java.util
import java.util.{Collection, Collections, HashSet, Set}

import com.ibm.wala.types.FieldReference

class IFDSDataFlow(val icfg: ExplodedInterproceduralCFG) {
  final private val cha = icfg.getCallGraph.getClassHierarchy
  final private val supergraph = ICFGSupergraph.make(icfg.getCallGraph)
  final private val domain = new DataFlowDomain
  final private val aliasMap = new util.HashMap[String, Map[Int, Int]]()
  // For each pair ((v, facts), idx) in domain (where v is a variable,
  // facts are facts v rely on, idx is the index of (v, facts) in domain),
  // there would be (v, set) in factNumMap, where idx is in set, so that we
  // could easily find the
  // idx of for v, and thus find the facts
//  final private val factNumMap = new util.HashMap[Int, Int]()

  def printDomain() : Unit = {
    println("Domain:")
    println(domain)
    println("=========Domain finish======")
  }

  /**
   * controls numbering of putstatic instructions for use in tabulation
   */
  @SuppressWarnings(Array("serial")) class DataFlowDomain
    extends MutableMapping[Pair[Either[Int, FieldReference], Set[Either[Int, FieldReference]]]]
      with TabulationDomain[Pair[Either[Int, FieldReference], Set[Either[Int, FieldReference]]], BasicBlockInContext[IExplodedBasicBlock]] {
    override def hasPriorityOver(p1: PathEdge[BasicBlockInContext[IExplodedBasicBlock]], p2: PathEdge[BasicBlockInContext[IExplodedBasicBlock]]): Boolean = { // don't worry about worklist priorities
      false
    }
  }

  class DataFlowFunctions (val domain: DataFlowDomain) extends IPartiallyBalancedFlowFunctions[BasicBlockInContext[IExplodedBasicBlock]] {
    /**
     * the flow function for flow from a callee to caller where there was no flow from caller to callee; just the identity function
     *
     * @see ReachingDefsProblem
     */
    override def getUnbalancedReturnFlowFunction(src: BasicBlockInContext[IExplodedBasicBlock], dest: BasicBlockInContext[IExplodedBasicBlock]): IFlowFunction = IdentityFlowFunction.identity

    /**
     * flow function from caller to callee; just the identity function
     */
    override def getCallFlowFunction(src: BasicBlockInContext[IExplodedBasicBlock], dest: BasicBlockInContext[IExplodedBasicBlock], ret: BasicBlockInContext[IExplodedBasicBlock]): IUnaryFlowFunction = IdentityFlowFunction.identity

    /**
     * flow function from call node to return node when there are no targets for the call site; not a case we are expecting
     */
    override def getCallNoneToReturnFlowFunction(src: BasicBlockInContext[IExplodedBasicBlock], dest: BasicBlockInContext[IExplodedBasicBlock]): IUnaryFlowFunction = { // if we're missing callees, just keep what information we have
      IdentityFlowFunction.identity
    }

    /**
     * flow function from call node to return node at a call site when callees exist. We kill everything; surviving facts should
     * flow out of the callee
     */
    override def getCallToReturnFlowFunction(src: BasicBlockInContext[IExplodedBasicBlock], dest: BasicBlockInContext[IExplodedBasicBlock]): IUnaryFlowFunction = KillEverything.singleton

    /**
     * flow function for normal intraprocedural edges
     */
    override def getNormalFlowFunction(src: BasicBlockInContext[IExplodedBasicBlock], dest: BasicBlockInContext[IExplodedBasicBlock]): IUnaryFlowFunction = {
      val symTable = src.getNode.getIR.getSymbolTable
      d1: Int => {
        val fact = domain.getMappedObject(d1)
        val instr = src.getDelegate.getInstruction
        val result = MutableSparseIntSet.makeEmpty
        if (instr == null || instr.getNumberOfUses < 1 || instr.isInstanceOf[JavaScriptCheckReference]
          || instr.isInstanceOf[SetPrototype]
          || instr.isInstanceOf[SSAConditionalBranchInstruction]
          || instr.isInstanceOf[AstLexicalWrite] || instr.isInstanceOf[JavaScriptTypeOfInstruction]
          || instr.isInstanceOf[EachElementGetInstruction]) {
          // do nothing
        } else if (instr.isInstanceOf[PrototypeLookup]) {

        }
        else instr match {
          case getInstr: SSAGetInstruction =>
            val from = getInstr.getUse(0)
            val to = getInstr.getDef
            val factNum = domain.add(Pair.make(Left(to), Collections.singleton(Left(from))))
            result.add(factNum)
//            factNumMap.put(to, factNum)
          case putInstr: SSAPutInstruction =>
            if (putInstr.getNumberOfUses > 1) {
              val from = putInstr.getUse(1)
              val to = putInstr.getUse(0)
              val factNum = domain.add(Pair.make(Left(to), Collections.singleton(Left(from))))
              result.add(factNum)
//              factNumMap.put(to, factNum)
            }
          case write: JavaScriptPropertyWrite =>
            val from = write.getUse(2)
            val to = write.getObjectRef
            val factNum = domain.add(Pair.make(Left(to), Collections.singleton(Left(from))))
            result.add(factNum)
//            factNumMap.put(to, factNum)
          case read: JavaScriptPropertyRead =>
            val from = read.getObjectRef
            val to = read.getDef
            val factNum = domain.add(Pair.make(Left(to), Collections.singleton(Left(from))))
            result.add(factNum)
//            factNumMap.put(to, factNum)
          case _: SSAReturnInstruction => // move the kill statement to the end of the function
          case _: SSABinaryOpInstruction =>
            val from1 = instr.getUse(0)
            val from2 = instr.getUse(1)
            val to = instr.getDef
            if (from1 == fact.fst || from2 == fact.fst) result.add(domain.add(Pair.make(Left(to), fact.snd)))
            if (symTable.isConstant(from1)) result.add(domain.add(Pair.make(Left(to), Collections.singleton(Left(from1)))))
            if (symTable.isConstant(from2)) result.add(domain.add(Pair.make(Left(to), Collections.singleton(Left(from2)))))
          case _: SSAUnaryOpInstruction =>
            val from1 = instr.getUse(0)
            val to = instr.getDef

            val factNum = domain.add(Pair.make(Left(to), Collections.singleton(Left(from1))))
            result.add(factNum)
          case _ => //          throw new RuntimeException(instr.toString() + ", " + instr.getClass().toString());
            System.out.println("Unhandled getNormalFlowFunction: " + instr.toString + ", " + instr.getClass.toString)
        }
        if (!instr.isInstanceOf[SSAReturnInstruction]) {
          result.add(d1)
        }
        result
      }
    }

    /**
     * standard flow function from callee to caller; just identity
     */
    override def getReturnFlowFunction(call: BasicBlockInContext[IExplodedBasicBlock],
                                       src: BasicBlockInContext[IExplodedBasicBlock],
                                       dest: BasicBlockInContext[IExplodedBasicBlock]): IFlowFunction = IdentityFlowFunction.identity
  }

  /**
   * Definition of the reaching definitions tabulation problem. Note that we choose to make the problem a <em>partially</em>
   * balanced tabulation problem, where the solver is seeded with the putstatic instructions themselves. The problem is partially
   * balanced since a definition in a callee used as a seed for the analysis may then reach a caller, yielding a "return" without a
   * corresponding "call." An alternative to this approach, used in the Reps-Horwitz-Sagiv POPL95 paper, would be to "lift" the
   * domain of putstatic instructions with a 0 (bottom) element, have a 0->0 transition in all transfer functions, and then seed the
   * analysis with the path edge (main_entry, 0) -> (main_entry, 0). We choose the partially-balanced approach to avoid pollution of
   * the flow functions.
   *
   */
  private class ReachingDefsProblem extends PartiallyBalancedTabulationProblem[BasicBlockInContext[IExplodedBasicBlock], CGNode, Pair[Either[Int, FieldReference], Set[Either[Int, FieldReference]]]] {
    private val flowFunctions = new DataFlowFunctions(domain)
    /**
     * path edges corresponding to all putstatic instructions, used as seeds for the analysis
     */
    private val initialSeedsVal: Collection[PathEdge[BasicBlockInContext[IExplodedBasicBlock]]] = collectInitialSeeds

    /**
     * collect the putstatic instructions in the call graph as {@link PathEdge} seeds for the analysis
     */
    private def collectInitialSeeds: Collection[PathEdge[BasicBlockInContext[IExplodedBasicBlock]]] = {
      val result:Collection[PathEdge[BasicBlockInContext[IExplodedBasicBlock]]] = new HashSet[PathEdge[BasicBlockInContext[IExplodedBasicBlock]]]()
      val itr = supergraph.getProcedureGraph.iterator
      while (itr.hasNext) {
        val cgNode = itr.next()
        val fakeEntry = getFakeEntry(cgNode)
        val factNum = domain.add(Pair.make(Left(0), new HashSet[Either[Int, FieldReference]]))
        icfg.getSuccNodes(fakeEntry).forEachRemaining((succ: BasicBlockInContext[IExplodedBasicBlock]) => {
          def foo(succ: BasicBlockInContext[IExplodedBasicBlock]) = {
            val entryInstr = fakeEntry.getDelegate.getInstruction
            val succInstr = succ.getDelegate.getInstruction
            result.add(PathEdge.createPathEdge(fakeEntry, factNum, succ, factNum))
          }
          foo(succ)
        })
      }
      result
    }

    /**
     * we use the entry block of the CGNode as the fake entry when propagating from callee to caller with unbalanced parens
     */
    override def getFakeEntry(node: BasicBlockInContext[IExplodedBasicBlock]): BasicBlockInContext[IExplodedBasicBlock] = {
      val cgNode = node.getNode
      getFakeEntry(cgNode)
    }

    /**
     * we use the entry block of the CGNode as the "fake" entry when propagating from callee to caller with unbalanced parens
     */
    private def getFakeEntry(cgNode: CGNode) = {
      val entriesForProcedure = supergraph.getEntriesForProcedure(cgNode)
      assert(entriesForProcedure.length == 1)
      entriesForProcedure(0)
    }

    override def getFunctionMap: IPartiallyBalancedFlowFunctions[BasicBlockInContext[IExplodedBasicBlock]] = flowFunctions

    override def getDomain: TabulationDomain[Pair[Either[Int, FieldReference], Set[Either[Int, FieldReference]]], BasicBlockInContext[IExplodedBasicBlock]] = domain

    /**
     * we don't need a merge function; the default unioning of tabulation works fine
     */
    override def getMergeFunction: IMergeFunction = null

    override def getSupergraph: ISupergraph[BasicBlockInContext[IExplodedBasicBlock], CGNode] = supergraph

    override def initialSeeds: Collection[PathEdge[BasicBlockInContext[IExplodedBasicBlock]]] = initialSeedsVal
  }

  /**
   * perform the tabulation analysis and return the {@link TabulationResult}
   */
  def analyze: TabulationResult[BasicBlockInContext[IExplodedBasicBlock], CGNode, Pair[Either[Int, FieldReference], Set[Either[Int, FieldReference]]]] = {
    val solver = PartiallyBalancedTabulationSolver.createPartiallyBalancedTabulationSolver(new ReachingDefsProblem, null)
    var result: TabulationResult[BasicBlockInContext[IExplodedBasicBlock], CGNode, Pair[Either[Int, FieldReference], Set[Either[Int, FieldReference]]]] = null
    try result = solver.solve
    catch {
      case e: CancelException =>
        // this shouldn't happen
        assert(false)
    }
    result
  }

  def getSupergraph: ISupergraph[BasicBlockInContext[IExplodedBasicBlock], CGNode] = supergraph

  def getDomain: TabulationDomain[Pair[Either[Int, FieldReference], Set[Either[Int, FieldReference]]], BasicBlockInContext[IExplodedBasicBlock]] = domain
}
