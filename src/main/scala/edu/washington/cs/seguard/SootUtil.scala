package edu.washington.cs.seguard

import soot.SootMethod
import soot.jimple.InvokeExpr

object SootUtil {
  /**
   * getMethod will throw exception sometimes...
   *
   * FIXME: upgrade Soot
   *
   * @param invokeExpr
   * @return
   */
  def getMethodUnsafe(invokeExpr: InvokeExpr): SootMethod = {
    try {
      invokeExpr.getMethod
    } catch {
      case ignored: Exception =>
        println(ignored)
        null
    }
  }
}