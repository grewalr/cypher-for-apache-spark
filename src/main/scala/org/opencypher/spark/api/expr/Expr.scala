package org.opencypher.spark.api.expr

import org.opencypher.spark.api.ir.global.{ConstantRef, LabelRef, PropertyKeyRef, RelTypeRef}
import org.opencypher.spark.api.types._

import scala.annotation.tailrec

sealed trait Expr {
  def cypherType: CypherType
}

final case class Const(ref: ConstantRef, cypherType: CypherType = CTWildcard) extends Expr
final case class Var(name: String, cypherType: CypherType = CTWildcard) extends Expr {
  override def toString = name
}
final case class StartNode(e: Expr, cypherType: CypherType = CTWildcard) extends Expr {
  override def toString = s"source($e)"
}
final case class EndNode(e: Expr, cypherType: CypherType = CTWildcard) extends Expr {
  override def toString = s"target($e)"
}

trait FlatteningOpExprCompanion[T] {
  def apply(exprs: Expr*): T
  def apply(exprs: Set[Expr]): T
  def unapply(expr: Any): Option[Set[Expr]]
}

sealed abstract class FlatteningOpExpr(_exprs: Set[Expr]) extends Expr with Serializable with Product1[Set[Expr]] {

  val exprs: Set[Expr] =
    if (_exprs.isEmpty) throw new IllegalStateException(s"Attempt to construct empty $productPrefix")
    else flatExpr(_exprs)

  override def _1: Set[Expr] = exprs

  override def equals(obj: scala.Any) = companion.unapply(obj).contains(exprs)
  override def hashCode() = exprs.hashCode() + hashPrime
  override def toString = s"$productPrefix(${exprs.mkString(", ")})"

  protected def companion: FlatteningOpExprCompanion[_]
  protected def hashPrime: Int

  @tailrec
  private def flatExpr(exprs: Set[Expr], result: Set[Expr] = Set.empty): Set[Expr] =
    if (exprs.isEmpty)
      result
    else {
      val expr = exprs.head
      val remaining = exprs.tail
      companion.unapply(expr) match  {
        case Some(moreExprs) => flatExpr(moreExprs ++ remaining, result)
        case None => flatExpr(remaining, result + expr)
      }
    }
}

object Ands extends FlatteningOpExprCompanion[Ands] {
  override def apply(exprs: Expr*): Ands = Ands(exprs.toSet)
  override def apply(exprs: Set[Expr]): Ands = new Ands(exprs)
  override def unapply(expr: Any): Option[Set[Expr]] = expr match {
    case ands: Ands => Some(ands.exprs)
    case _ => None
  }
}

final class Ands(_exprs: Set[Expr], override val cypherType: CypherType = CTWildcard) extends FlatteningOpExpr(_exprs) {
  override def productPrefix = "Ands"
  override def canEqual(that: Any): Boolean = that.isInstanceOf[Ands]
  override protected def companion: FlatteningOpExprCompanion[Ands] = Ands
  override protected def hashPrime: Int = 31
}

object Ors extends FlatteningOpExprCompanion[Ors] {
  override def apply(exprs: Expr*): Ors = Ors(exprs.toSet)
  override def apply(exprs: Set[Expr]): Ors = new Ors(exprs)
  override def unapply(expr: Any): Option[Set[Expr]] = expr match {
    case ors: Ors => Some(ors.exprs)
    case _ => None
  }
}

final class Ors(_exprs: Set[Expr], override val cypherType: CypherType = CTWildcard) extends FlatteningOpExpr(_exprs) {
  override def productPrefix = "Ors"
  override def canEqual(that: Any): Boolean = that.isInstanceOf[Ors]
  override protected def companion: FlatteningOpExprCompanion[Ors] = Ors
  override protected def hashPrime: Int = 61
}

final case class Not(expr: Expr, cypherType: CypherType = CTWildcard) extends Expr {
  override def toString = s"NOT $expr"
}

final case class HasLabel(node: Expr, label: LabelRef, cypherType: CypherType = CTWildcard) extends Expr {
  override def toString = s"$node:${label.id}"
}
final case class HasType(rel: Expr, relType: RelTypeRef, cypherType: CypherType = CTWildcard) extends Expr
final case class Equals(lhs: Expr, rhs: Expr, cypherType: CypherType = CTWildcard) extends Expr
final case class Property(m: Expr, key: PropertyKeyRef, cypherType: CypherType = CTWildcard) extends Expr {
  override def toString = s"$m.${key.id}"
}
final case class TypeId(rel: Expr, cypherType: CypherType = CTWildcard) extends Expr {
  override def toString = s"type($rel)"
}

sealed trait Lit[T] extends Expr {
  def v: T
}

final case class IntegerLit(v: Long, cypherType: CypherType = CTWildcard) extends Lit[Long]
final case class StringLit(v: String, cypherType: CypherType = CTWildcard) extends Lit[String]

sealed abstract class BoolLit(val v: Boolean) extends Lit[Boolean]
final case class TrueLit(cypherType: CypherType = CTWildcard) extends BoolLit(true)
final case class FalseLit(cypherType: CypherType = CTWildcard) extends BoolLit(false)