package edu.washington.cs.seguard

import soot.SootMethod
import soot.jimple.IntConstant
import scala.collection.JavaConverters._

sealed abstract class Abstraction extends Product with Serializable

object Abstraction {
  final case class StringConstant(str: String) extends Abstraction {
    override def toString: String = str

  }
  final case class IntegerConstant(integer: Int) extends Abstraction {
    override def toString: String = integer.toString
  }
  final case class MethodConstant(method: SootMethod) extends Abstraction {
    override def toString: String = method.getSignature
  }

  def v(config: Config, stringConstant: String) : Abstraction = {
    for (keyword <- config.getSensitiveConstStringKeywords.asScala) {
      if (stringConstant.contains(keyword)) {
        return StringConstant(stringConstant)
      }
    }
    if (stringConstant.matches("\\d+")) {
      return StringConstant("INTEGER_STR")
    }
    StringConstant("[other]")
  }

  def v(intConstant: IntConstant) : Abstraction = {
    IntegerConstant(intConstant.value)
  }

  def v(method: SootMethod) : Abstraction = {
    MethodConstant(method)
  }
}