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
import com.ibm.wala.util.intset.{IntSet, MutableMapping, MutableSparseIntSet}
import java.util
import java.util.{Collection, Collections, HashSet, Set}

class IFDSDataFlow(val icfg: ExplodedInterproceduralCFG) {
  final private val supergraph = ICFGSupergraph.make(icfg.getCallGraph)
  final private val domain = new DataFlowDomain

  /**
   * controls numbering of putstatic instructions for use in tabulation
   * TODO: use sum type instead of Either
   */
  @SuppressWarnings(Array("serial")) class DataFlowDomain
    extends MutableMapping[Pair[Either[Int, String], Set[Either[Int, String]]]]
      with TabulationDomain[Pair[Either[Int, String], Set[Either[Int, String]]], BasicBlockInContext[IExplodedBasicBlock]] {
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
      // TODO: imprecision for both application API and platform API
      val instr = src.getDelegate.getInstruction
      instr match {
        case invoke:JavaScriptInvoke =>
          d1: Int => {
            val fact = domain.getMappedObject(d1)
            val result = MutableSparseIntSet.makeEmpty
            result.add(d1)
            fact.fst match {
              case Left(i) =>
                if (invoke.getNumberOfReturnValues > 1) {
                  for(paramIdx <- 1 until invoke.getNumberOfPositionalParameters){
                    val param = invoke.getUse(paramIdx)
                    // if i is one of the params of the function call
                    if (param == i) {
                      val deps = new util.HashSet[Either[Int, String]](fact.snd)
                      deps.add(Left(param))
                      val to = invoke.getReturnValue(0)
                      val factNum = domain.add(Pair.make(Left(to), deps))
                      result.add(factNum)
                    }
                  }
                }
              case _ =>
            }
            result
          }
        case _ => IdentityFlowFunction.identity
      }
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
      new IUnaryFlowFunction {
        override def getTargets(inputDomain: Int): IntSet = {
          val fact = domain.getMappedObject(inputDomain)
          val instr = src.getDelegate.getInstruction
          val result = MutableSparseIntSet.makeEmpty
          val tainted = fact.fst
          val taint = fact.snd
          if (instr == null || (instr.getNumberOfUses < 1 && !instr.isInstanceOf[AstGlobalRead])
            || instr.isInstanceOf[JavaScriptCheckReference] || instr.isInstanceOf[SetPrototype]
            || instr.isInstanceOf[SSAConditionalBranchInstruction]
            || instr.isInstanceOf[AstLexicalWrite] || instr.isInstanceOf[JavaScriptTypeOfInstruction]
            || instr.isInstanceOf[EachElementGetInstruction]) {
            // do nothing
          } else instr match {
            case lookup: PrototypeLookup =>
              val lhs = lookup.getDef(0)
              val rhs = lookup.getUse(0)
              tainted match {
                case Left(i) =>
                  if (i == rhs || i == 0) {
                    val factNum = domain.add(Pair.make(Left(lhs), taint))
                    result.add(factNum)
                  }
                  if (i != lhs) {
                    result.add(inputDomain)
                  }
                case _ =>
              }
              return result
            case readInstr: AstGlobalRead =>
              val from = readInstr.getGlobalName
              tainted match {
                case Right(s) =>
                  val to = readInstr.getDef
                  if (s == from) {
                    val deps = new HashSet[Either[Int, String]](taint)
                    deps.add(Right(from))
                    val factNum = domain.add(Pair.make(Left(to), deps))
                    result.add(factNum)
                  } else {
                    result.add(domain.add(Pair.make(Left(to), Collections.singleton(Right(from)))))
                  }
                case _ =>
              }
            case writeInstr: AstGlobalWrite =>
              val from = writeInstr.getVal
              tainted match {
                case Left(i) =>
                  if (i == from || i == 0) {
                    val to = writeInstr.getGlobalName
                    val factNum = domain.add(Pair.make(Right(to), taint))
                    result.add(factNum)
                  }
                case _ =>
              }
            case getInstr: SSAGetInstruction =>
              val from = getInstr.getRef
              tainted match {
                case Left(i) =>
                  if (i == from || i == 0) {
                    val to = getInstr.getDef
                    val deps = new HashSet[Either[Int, String]](taint)
                    deps.add(Left(from))
                    if (!getInstr.isStatic) {
                      deps.add(Right("\"" + getInstr.getDeclaredField.getName + "\""))
                    }
                    val factNum = domain.add(Pair.make(Left(to), deps))
                    result.add(factNum)
                  }
                case _ =>
              }
            case putInstr: SSAPutInstruction =>
              if (putInstr.getNumberOfUses > 1) {
                val from = putInstr.getUse(1)
                tainted match {
                  case Left(i) =>
                    if (i == from || i == 0) {
                      val to = putInstr.getUse(0)
                      val deps = new HashSet[Either[Int, String]](taint)
                      deps.add(Left(from))
                      deps.add(Right("\"" + putInstr.getDeclaredField.getName + "\""))
                      val factNum = domain.add(Pair.make(Left(to), deps))
                      result.add(factNum)
                    }
                  case _ =>
                }
              }
            case write: JavaScriptPropertyWrite =>
              val from = write.getUse(2)
              tainted match {
                case Left(i) =>
                  if (i == from || i == 0) {
                    val to = write.getObjectRef
                    val deps = new HashSet[Either[Int, String]](taint)
                    deps.add(Left(from))
                    deps.add(Left(write.getMemberRef))
                    val factNum = domain.add(Pair.make(Left(to), deps))
                    result.add(factNum)
                  }
                case _ =>
              }
            case read: JavaScriptPropertyRead =>
              val from = read.getObjectRef
              tainted match {
                case Left(i) =>
                  if (i == from || i == 0) {
                    val to = read.getDef
                    val deps = new HashSet[Either[Int, String]](taint)
                    deps.add(Left(from))
                    deps.add(Left(read.getMemberRef))
                    val factNum = domain.add(Pair.make(Left(to), deps))
                    result.add(factNum)
                  }
                case _ =>
              }
            case _: SSAReturnInstruction => // move the kill statement to the end of the function
            case _: SSABinaryOpInstruction =>
              val from1 = instr.getUse(0)
              val from2 = instr.getUse(1)
              val to = instr.getDef
              tainted match {
                case Left(i) =>
                  if (i == 0) {
                    val deps = new HashSet[Either[Int, String]]()
                    deps.add(Left(from1))
                    deps.add(Left(from2))
                    result.add(domain.add(Pair.make(Left(to), deps)))
                  }
                  if (i == from1 || i == from2) {
                    val to = instr.getDef
                    result.add(domain.add(Pair.make(Left(to), taint)))
                  }
                  if (symTable.isConstant(from1)) {
                    result.add(domain.add(Pair.make(Left(to), Collections.singleton(Left(from1)))))
                  }
                  if (symTable.isConstant(from2)) {
                    result.add(domain.add(Pair.make(Left(to), Collections.singleton(Left(from2)))))
                  }
                case _ =>
              }
            case _: SSAUnaryOpInstruction =>
              val from1 = instr.getUse(0)
              tainted match {
                case Left(i) =>
                  val to = instr.getDef
                  if (i == from1 || i == 0) {
                    val deps = new HashSet[Either[Int, String]](taint)
                    deps.add(Left(from1))
                    val factNum = domain.add(Pair.make(Left(to), deps))
                    result.add(factNum)
                  }
                  if (symTable.isConstant(from1)) {
                    result.add(domain.add(Pair.make(Left(to), Collections.singleton(Left(from1)))))
                  }
                case _ =>
              }
            case _ =>
              val instrString = instr.toString
              if (!instrString.contains("throw ") && !instrString.contains("is instance of ") &
                !instrString.contains("isDefined")) {
                System.out.println("Unhandled getNormalFlowFunction: " + instrString + ", " + instr.getClass.toString)
              }
          }
          // we need to kill at assignment
          if (!instr.isInstanceOf[SSAReturnInstruction]) {
            result.add(inputDomain)
          }
          result
        }
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
  private class ReachingDefsProblem extends PartiallyBalancedTabulationProblem[BasicBlockInContext[IExplodedBasicBlock], CGNode, Pair[Either[Int, String], Set[Either[Int, String]]]] {
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
        val factNum = domain.add(Pair.make(Left(0), new HashSet[Either[Int, String]]))
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

    override def getDomain: TabulationDomain[Pair[Either[Int, String], Set[Either[Int, String]]], BasicBlockInContext[IExplodedBasicBlock]] = domain

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
  def analyze: TabulationResult[BasicBlockInContext[IExplodedBasicBlock], CGNode, Pair[Either[Int, String], Set[Either[Int, String]]]] = {
  import com.ibm.wala.util.intset.IntSet
    val solver = PartiallyBalancedTabulationSolver.createPartiallyBalancedTabulationSolver(new ReachingDefsProblem, null)
    var result: TabulationResult[BasicBlockInContext[IExplodedBasicBlock], CGNode, Pair[Either[Int, String], Set[Either[Int, String]]]] = null
    try result = solver.solve
    catch {
      case e: CancelException =>
        // this shouldn't happen
        assert(false)
    }
    result
  }

  def getSupergraph: ISupergraph[BasicBlockInContext[IExplodedBasicBlock], CGNode] = supergraph

  def getDomain: TabulationDomain[Pair[Either[Int, String], Set[Either[Int, String]]], BasicBlockInContext[IExplodedBasicBlock]] = domain
}
