package edu.washington.cs.seguard.js

import com.ibm.wala.cast.ir.ssa.{AstGlobalRead, AstGlobalWrite, AstLexicalWrite, EachElementGetInstruction}
import com.ibm.wala.cast.js.ssa._
import com.ibm.wala.dataflow.IFDS._
import com.ibm.wala.ipa.callgraph.CGNode
import com.ibm.wala.ipa.cfg.BasicBlockInContext
import com.ibm.wala.ipa.cfg.ExplodedInterproceduralCFG
import com.ibm.wala.ssa._
import com.ibm.wala.ssa.analysis.IExplodedBasicBlock
import com.ibm.wala.util.collections.Pair
import com.ibm.wala.util.intset.{IntSet, MutableMapping, MutableSparseIntSet}
import java.util
import java.util.{Collection, Collections, HashSet}

sealed abstract class AbsVar extends Product with Serializable

object AbsVar {
  final case class Global(name: String) extends AbsVar

  /**
   * @param idx SSA index
   */
  final case class Local(idx: Int) extends AbsVar

  final case class Constant(idx: Int) extends AbsVar
}

class IFDSDataFlow(val icfg: ExplodedInterproceduralCFG) {
  final private val supergraph = ICFGSupergraph.make(icfg.getCallGraph)
  final private val domain = new DataFlowDomain
  type Block = BasicBlockInContext[IExplodedBasicBlock]

  /**
   * controls numbering of putstatic instructions for use in tabulation
   * TODO: use sum type instead of Either
   */
  @SuppressWarnings(Array("serial")) class DataFlowDomain
    extends MutableMapping[Pair[AbsVar, util.Set[AbsVar]]]
      with TabulationDomain[Pair[AbsVar, util.Set[AbsVar]], Block] {
    override def hasPriorityOver(p1: PathEdge[Block], p2: PathEdge[Block]): Boolean = { // don't worry about worklist priorities
      false
    }
  }

  class DataFlowFunctions (val domain: DataFlowDomain) extends IPartiallyBalancedFlowFunctions[Block] {
    /**
     * the flow function for flow from a callee to caller where there was no flow from caller to callee; just the identity function
     *
     * @see ReachingDefsProblem
     */
    override def getUnbalancedReturnFlowFunction(src: Block, dest: Block): IFlowFunction = IdentityFlowFunction.identity

    /**
     * flow function from caller to callee; just the identity function
     */
    override def getCallFlowFunction(src: Block, dest: Block, ret: Block): IUnaryFlowFunction = IdentityFlowFunction.identity

    /**
     * flow function from call node to return node when there are no targets for the call site; not a case we are expecting
     */
    override def getCallNoneToReturnFlowFunction(src: Block, dest: Block): IUnaryFlowFunction = { // if we're missing callees, just keep what information we have
      // TODO: imprecision for both application API and platform API
//      val instr = src.getDelegate.getInstruction
//      instr match {
//        case invoke:JavaScriptInvoke =>
//          d1: Int => {
//            val fact = domain.getMappedObject(d1)
//            val result = MutableSparseIntSet.makeEmpty
//            result.add(d1)
//            fact.fst match {
//              case AbsVar.Local(i) =>
//                if (invoke.getNumberOfReturnValues > 1) {
//                  for(paramIdx <- 1 until invoke.getNumberOfPositionalParameters){
//                    val param = invoke.getUse(paramIdx)
//                    // if i is one of the params of the function call
//                    if (param == i) {
//                      val deps = new util.HashSet[AbsVar](fact.snd)
//                      deps.add(AbsVar.Local(param))
//                      val to = invoke.getReturnValue(0)
//                      val factNum = domain.add(Pair.make(AbsVar.Local(to), deps))
//                      result.add(factNum)
//                    }
//                  }
//                }
//              case _ =>
//            }
//            result
//          }
//        case _ => IdentityFlowFunction.identity
//      }
      IdentityFlowFunction.identity
    }

    /**
     * flow function from call node to return node at a call site when callees exist. We kill everything; surviving facts should
     * flow out of the callee
     */
    override def getCallToReturnFlowFunction(src: Block, dest: Block): IUnaryFlowFunction = KillEverything.singleton

    /**
     * flow function for normal intraprocedural edges
     */
    override def getNormalFlowFunction(src: Block, dest: Block): IUnaryFlowFunction = {
      val symTable = src.getNode.getIR.getSymbolTable
      new IUnaryFlowFunction {
        override def getTargets(inputDomain: Int): IntSet = {
          val fact = domain.getMappedObject(inputDomain)
          val instr = src.getDelegate.getInstruction
          val result = MutableSparseIntSet.makeEmpty
          val tainted: AbsVar = fact.fst
          val taint: util.Set[AbsVar] = fact.snd
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
                case AbsVar.Local(i) =>
                  if (i == rhs || i == 0) {
                    val factNum = domain.add(Pair.make(AbsVar.Local(lhs), taint))
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
                case AbsVar.Global(s) =>
                  val to = readInstr.getDef
                  if (s == from) {
                    val deps = new util.HashSet[AbsVar](taint)
                    deps.add(AbsVar.Global(from))
                    val factNum = domain.add(Pair.make(AbsVar.Local(to), deps))
                    result.add(factNum)
                  } else {
                    result.add(domain.add(Pair.make(AbsVar.Local(to), Collections.singleton(AbsVar.Global(from)))))
                  }
                case _ =>
              }
            case writeInstr: AstGlobalWrite =>
              val from = writeInstr.getVal
              tainted match {
                case AbsVar.Local(i) =>
                  if (i == from || i == 0) {
                    val to = writeInstr.getGlobalName
                    val factNum = domain.add(Pair.make(AbsVar.Global(to), taint))
                    result.add(factNum)
                  }
                case _ =>
              }
            case getInstr: SSAGetInstruction =>
              val lhs = getInstr.getDef
              tainted match {
                case AbsVar.Local(i) =>
                  if (i == 0) {
                    val deps = new util.HashSet[AbsVar](taint)
                    if (!getInstr.isStatic) {
                      deps.add(AbsVar.Global("\"" + getInstr.getDeclaredField.getName + "\""))
                    }
                    val factNum = domain.add(Pair.make(AbsVar.Local(lhs), deps))
                    result.add(factNum)
                  }
                  if (i != lhs) {
                    result.add(inputDomain)
                  }
                case _ =>
              }
              return result
            case putInstr: SSAPutInstruction =>
              if (putInstr.getNumberOfUses > 1) {
                val from = putInstr.getUse(1)
                tainted match {
                  case AbsVar.Local(i) =>
                    if (i == from || i == 0) {
                      val to = putInstr.getUse(0)
                      val deps = new util.HashSet[AbsVar](taint)
                      deps.add(AbsVar.Global("\"" + putInstr.getDeclaredField.getName + "\""))
                      val factNum = domain.add(Pair.make(AbsVar.Local(to), deps))
                      result.add(factNum)
                    }
                  case _ =>
                }
              }
//            case write: JavaScriptPropertyWrite =>
//              // NOTE: this branch seems like a hot plate
//              val from = write.getUse(2)
//              tainted match {
//                case AbsVar.Local(i) =>
//                  if (i == from || i == 0) {
//                    val to = write.getObjectRef
//                    val deps = new util.HashSet[AbsVar](taint)
//                    deps.add(AbsVar.Local(from))
//                    deps.add(AbsVar.Local(write.getMemberRef))
//                    val factNum = domain.add(Pair.make(AbsVar.Local(to), deps))
//                    result.add(factNum)
//                  }
//                case _ =>
//              }
//            case read: JavaScriptPropertyRead =>
//              val from = read.getObjectRef
//              tainted match {
//                case AbsVar.Local(i) =>
//                  if (i == from || i == 0) {
//                    val to = read.getDef
//                    val deps = new util.HashSet[AbsVar](taint)
//                    deps.add(AbsVar.Local(from))
//                    deps.add(AbsVar.Local(read.getMemberRef))
//                    val factNum = domain.add(Pair.make(AbsVar.Local(to), deps))
//                    result.add(factNum)
//                  }
//                case _ =>
//              }
            case _: SSABinaryOpInstruction =>
              val from1 = instr.getUse(0)
              val from2 = instr.getUse(1)
              val lhs = instr.getDef
              tainted match {
                case AbsVar.Local(i) =>
                  if (i == from1 || i == from2) {
                    result.add(domain.add(Pair.make(AbsVar.Local(lhs), taint)))
                  }
                  // i == 0 iff. zero input domain
                  if (i == 0) {
                    if (symTable.isConstant(from1)) {
                      result.add(domain.add(Pair.make(AbsVar.Local(lhs), Collections.singleton(AbsVar.Local(from1)))))
                    }
                    if (symTable.isConstant(from2)) {
                      result.add(domain.add(Pair.make(AbsVar.Local(lhs), Collections.singleton(AbsVar.Local(from2)))))
                    }
                  }
                  if (i != lhs) {
                    result.add(inputDomain)
                  }
                case _ =>
                  println(instr.toString(symTable))
              }
              return result
            case _: SSAUnaryOpInstruction =>
              val from1 = instr.getUse(0)
              val lhs = instr.getDef
              tainted match {
                case AbsVar.Local(i) =>
                  if (i == from1) {
                    val deps = new util.HashSet[AbsVar](taint)
                    val factNum = domain.add(Pair.make(AbsVar.Local(lhs), deps))
                    result.add(factNum)
                  }
                  if (i == 0 && symTable.isConstant(from1)) {
                    result.add(domain.add(Pair.make(AbsVar.Local(lhs), Collections.singleton(AbsVar.Local(from1)))))
                  }
                  if (i != lhs) {
                    result.add(inputDomain)
                  }
                case _ =>
              }
            case _ =>
              val instrString = instr.toString
              if (!instrString.contains("throw ") && !instrString.contains("is instance of ") &
                !instrString.contains("isDefined")) {
//                System.out.println("Unhandled getNormalFlowFunction: " + instrString + ", " + instr.getClass.toString)
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
    override def getReturnFlowFunction(call: Block,
                                       src: Block,
                                       dest: Block): IFlowFunction = IdentityFlowFunction.identity
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
  class ReachingDefsProblem extends PartiallyBalancedTabulationProblem[Block, CGNode, Pair[AbsVar, util.Set[AbsVar]]] {
    private val flowFunctions = new DataFlowFunctions(domain)
    /**
     * path edges corresponding to all putstatic instructions, used as seeds for the analysis
     */
    private val initialSeedsVal: Collection[PathEdge[Block]] = collectInitialSeeds

    /**
     * collect the putstatic instructions in the call graph as {@link PathEdge} seeds for the analysis
     */
    private def collectInitialSeeds: Collection[PathEdge[Block]] = {
      val result:Collection[PathEdge[Block]] = new HashSet[PathEdge[Block]]()
      val itr = supergraph.getProcedureGraph.iterator
      while (itr.hasNext) {
        val cgNode = itr.next()
        val fakeEntry = getFakeEntry(cgNode)
        val factNum = domain.add(Pair.make(AbsVar.Local(0), new HashSet[AbsVar]()))
        icfg.getSuccNodes(fakeEntry).forEachRemaining((succ: Block) => {
          def foo(succ: Block) = {
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
    override def getFakeEntry(node: Block): Block = {
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

    override def getFunctionMap: IPartiallyBalancedFlowFunctions[Block] = flowFunctions

    override def getDomain: TabulationDomain[Pair[AbsVar, util.Set[AbsVar]], Block] = domain

    /**
     * we don't need a merge function; the default unioning of tabulation works fine
     */
    override def getMergeFunction: IMergeFunction = null

    override def getSupergraph: ISupergraph[Block, CGNode] = supergraph

    override def initialSeeds: Collection[PathEdge[Block]] = initialSeedsVal
  }

  val problem = new ReachingDefsProblem

  def solve: TabulationResult[BasicBlockInContext[IExplodedBasicBlock], CGNode, Pair[AbsVar, util.Set[AbsVar]]] = {
    PartiallyBalancedTabulationSolver.createPartiallyBalancedTabulationSolver(problem, null).solve()
  }
}
