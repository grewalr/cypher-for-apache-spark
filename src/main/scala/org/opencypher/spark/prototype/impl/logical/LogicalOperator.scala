package org.opencypher.spark.prototype.impl.logical

import org.opencypher.spark.prototype.api.expr._
import org.opencypher.spark.prototype.api.ir.SolvedQueryModel
import org.opencypher.spark.prototype.api.ir.pattern.EveryNode
import org.opencypher.spark.prototype.api.record.ProjectedSlotContent

import scala.language.implicitConversions

sealed trait LogicalOperator {
  def isLeaf = false
  def solved: SolvedQueryModel[Expr]
}

sealed trait StackingLogicalOperator extends LogicalOperator {
  def in: LogicalOperator
}

sealed trait LogicalLeafOperator extends LogicalOperator {
  override def isLeaf = true
}

final case class NodeScan(node: Var, nodeDef: EveryNode)
                         (override val solved: SolvedQueryModel[Expr]) extends LogicalLeafOperator {
}

final case class Filter(expr: Expr, in: LogicalOperator)
                       (override val solved: SolvedQueryModel[Expr]) extends StackingLogicalOperator {
}

sealed trait ExpandOperator extends StackingLogicalOperator {
  def source: Var
  def rel: Var
  def target: Var
}

final case class ExpandSource(source: Var, rel: Var, target: Var, in: LogicalOperator)
                             (override val solved: SolvedQueryModel[Expr])
  extends ExpandOperator {
}

final case class ExpandTarget(source: Var, rel: Var, target: Var, in: LogicalOperator)
                             (override val solved: SolvedQueryModel[Expr])
  extends ExpandOperator {
}

final case class Project(it: ProjectedSlotContent, in: LogicalOperator)
                        (override val solved: SolvedQueryModel[Expr]) extends StackingLogicalOperator {
}

final case class Select(fields: Seq[(Expr, String)], in: LogicalOperator)
                       (override val solved: SolvedQueryModel[Expr]) extends StackingLogicalOperator {
}
