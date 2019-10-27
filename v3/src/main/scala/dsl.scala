package org.bulatnig.v3.dsl

import org.bulatnig.v3.model.{Field, Format, Transaction}

import scala.reflect.runtime.currentMirror
import scala.reflect.runtime.universe._
import scala.tools.reflect.ToolBox

abstract class DslTransaction(protected val tx: Transaction)

trait TransactionAdapter {
  def wrap(tx: Transaction): DslTransaction
}

class Rule {
  def run()(implicit s: String): Unit = {
    println(s)
  }
}

class RuleEvaluator(format: Format, rule: String) {

  val (adapter, evaluator) = {
    val properties = format.fields.flatMap(field => {
      val scalaType = field.`type` match {
        case Field.Type.int => Select(Ident(TermName("scala")), TypeName("Int"))
        case Field.Type.string => Select(Ident(TermName("Predef")), TypeName("String"))
      }
      if (field.nullable) {
        List(
          q"val ${TermName(field.name + "_exists")}: Boolean = tx.data.contains(${Constant(field.name)})",
          q"def ${TermName(field.name)}(default: $scalaType): $scalaType = tx.data.getOrElse(${Constant(field.name)}, default).asInstanceOf[$scalaType]"
        )
      } else {
        List(q"val ${TermName(field.name)}: $scalaType = tx.data(${Constant(field.name)}).asInstanceOf[$scalaType]")
      }
    })
    val toolbox = currentMirror.mkToolBox()
    val adapterClass =
      q"""class DslTransactionImpl(tx: org.bulatnig.v3.model.Transaction)
         extends org.bulatnig.v3.dsl.DslTransaction(tx) { ..$properties }"""
    val adapterSymbol = toolbox.define(adapterClass.asInstanceOf[toolbox.u.ImplDef])
    val adapter = toolbox.eval(
      q"""new org.bulatnig.v3.dsl.TransactionAdapter {
            def wrap(tx: org.bulatnig.v3.model.Transaction): org.bulatnig.v3.dsl.DslTransaction =
                return new $adapterSymbol(tx)
          }""").asInstanceOf[TransactionAdapter]

    val ruleTree = toolbox.parse(rule)
    val evaluator = toolbox.eval(q"(tx: $adapterSymbol) => $ruleTree").asInstanceOf[DslTransaction => Boolean]
    (adapter, evaluator)
  }

  def evaluate(modelTx: Transaction): Boolean = {
    val dslTx = adapter.wrap(modelTx)
    evaluator(dslTx)
  }

}