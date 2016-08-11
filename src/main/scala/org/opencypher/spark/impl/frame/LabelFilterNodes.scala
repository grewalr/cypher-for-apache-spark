package org.opencypher.spark.impl.frame

import org.apache.spark.sql.Dataset
import org.opencypher.spark.CypherNode
import org.opencypher.spark.impl.frame.AllNodes.CypherNodes
import org.opencypher.spark.impl.{StdCypherFrame, StdRuntimeContext}

object LabelFilterNodes {
  def apply(input: CypherNodes, labels: Seq[String]): LabelFilterNodes = {
    new LabelFilterNodes(input, labels)
  }

  class LabelFilterNodes(input: CypherNodes, labels: Seq[String]) extends StdCypherFrame[CypherNode](input.signature) {

    override def run(implicit context: StdRuntimeContext): Dataset[CypherNode] = {
      val in = input.run
      val out = in.filter(HasLabels(labels))
      out
    }
  }

  private case class HasLabels(labels: Seq[String]) extends (CypherNode => Boolean) {

    override def apply(node: CypherNode): Boolean = {
      labels.forall(node.labels.contains)
    }
  }
}
