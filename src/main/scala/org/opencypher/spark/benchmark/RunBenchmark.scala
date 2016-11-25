package org.opencypher.spark.benchmark

import java.util.UUID

import org.apache.spark.SparkConf
import org.apache.spark.sql.SparkSession
import org.neo4j.driver.v1.{AuthTokens, GraphDatabase}
import org.opencypher.spark.api.value._
import org.opencypher.spark.benchmark.Converters.{internalNodeToAccessControlNode, internalNodeToCypherNode, internalRelToAccessControlRel, internalRelationshipToCypherRelationship}
import org.opencypher.spark.impl._

import scala.collection.immutable.IndexedSeq

object RunBenchmark {

  implicit var sparkSession: SparkSession = _

  def init() = {
    val conf = new SparkConf(true)
    conf.set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
    conf.set("spark.kryo.registrator", "org.opencypher.spark.CypherKryoRegistrar")
    conf.set("spark.neo4j.bolt.password", Neo4jPassword.get())
    conf.set("spark.driver.memory", "471859200")
    // Enable to see if we cover enough
    conf.set("spark.kryo.registrationRequired", "true")
    conf.set("spark.default.parallelism", Parallelism.get())

    //
    // This may or may not help - depending on the query
    // conf.set("spark.kryo.referenceTracking","false")
    //

    val builder = SparkSession.builder().config(conf)
    if (MasterAddress.get().nonEmpty)
      sparkSession = SparkSession.builder().config(conf).master(MasterAddress.get()).getOrCreate()
    else {
      //
      // If this is slow, you might be hitting: http://bugs.java.com/view_bug.do?bug_id=8077102
      //
      sparkSession = builder.master("local[*]").appName(s"cypher-on-spark-benchmark-${UUID.randomUUID()}").getOrCreate()
    }
    sparkSession.sparkContext.setLogLevel(Logging.get())

    sparkSession
  }

  def loadRDDs() = {
    val nodeRDD = sparkSession.sparkContext.objectFile[CypherNode](NodeFilePath.get())
    val relsRDD = sparkSession.sparkContext.objectFile[CypherRelationship](RelFilePath.get())

    Tests.printSums(nodeRDD, relsRDD)

    nodeRDD -> relsRDD
  }

  def createGraph(size: Long) = {
    val (allNodes, allRels) = loadRDDs()
    println("Nodes and relationships read from disk")

    val defaultParallelism: Int = sparkSession.sparkContext.defaultParallelism
    println(s"Parallelism: $defaultParallelism")

    val nodes = sparkSession.createDataset(allNodes)(CypherValue.Encoders.cypherNodeEncoder).limit(size.toInt).cache().repartition(defaultParallelism).cache()
    println(s"Finished creating dataset of ${nodes.count()} nodes")
    val relationships = sparkSession.createDataset(allRels)(CypherValue.Encoders.cypherRelationshipEncoder).limit(size.toInt).cache().repartition(defaultParallelism).cache()
    println(s"Finished creating dataset of ${relationships.count()} relationships")

    new StdPropertyGraph(nodes, relationships)
  }

  abstract class ConfigOption[T](val name: String, val defaultValue: T)(convert: String => Option[T]) {
    def get(): T = Option(System.getProperty(name)).flatMap(convert).getOrElse(defaultValue)
  }

  object GraphSize extends ConfigOption("cos.graph-size", 250000l)(x => Some(java.lang.Long.parseLong(x)))
  object MasterAddress extends ConfigOption("cos.master", "")(Some(_))
  object Logging extends ConfigOption("cos.logging", "OFF")(Some(_))
  object Parallelism extends ConfigOption("cos.parallelism", "8")(Some(_))
  object NodeFilePath extends ConfigOption("cos.nodeFile", "")(Some(_))
  object RelFilePath extends ConfigOption("cos.relFile", "")(Some(_))
  object Neo4jPassword extends ConfigOption("cos.neo4j-pw", ".")(Some(_))

  def createStdPropertyGraphFromNeo(size: Long) = {
    val session = sparkSession
    import CypherValue.Encoders._

    val parallelism = sparkSession.sparkContext.defaultParallelism

    val (nodeRDD, relRDD) = Importers.importFromNeo(size)
    val nMapped = nodeRDD.map(internalNodeToCypherNode)
    val rMapped = relRDD.map(internalRelationshipToCypherRelationship)

    val nodeSet = session.createDataset[CypherNode](nMapped)
    val cachedNodeSet = nodeSet.repartition(parallelism).cache()
    val relSet = session.createDataset[CypherRelationship](rMapped)
    val cachedRelSet = relSet.repartition(parallelism).cache()

    new StdPropertyGraph(cachedNodeSet, cachedRelSet)
  }

  def createSimpleDataFrameGraph(size: Long) = {
    val (nodeRDD, relRDD) = Importers.importFromNeo(size)
    val nMapped = nodeRDD.map(internalNodeToAccessControlNode)
    val rMapped = relRDD.map(internalRelToAccessControlRel)

//    val map = new mutable.HashMap[String, Seq[AccessControlRelationship]]
//    rels.collect().foreach {
//      case (t, rel) =>
//        val seq = map.getOrElse(t, Seq.empty[AccessControlRelationship])
//        map.put(t, seq :+ rel)
//    }
    val parallelism = sparkSession.sparkContext.defaultParallelism

    println("Repartitioning...")
    val nodeFrame = sparkSession.createDataFrame(nMapped)
    val idCol = nodeFrame.col("id")
    val cachedNodeFrame = nodeFrame.repartition(parallelism, idCol).sortWithinPartitions(idCol).cache()
    val relFrame = sparkSession.createDataFrame(rMapped)
    val startIdCol = relFrame.col("startId")
    val cachedRelFrame = relFrame.repartition(parallelism, startIdCol).sortWithinPartitions(startIdCol).cache()
//    val relFrames = map.map {
//      case (t, r) => t -> sparkSession.createDataFrame(r)
//    }
//    println(s"Done creating dataframes for $size relationships (${map.size} unique types)")

    SimpleDataFrameGraph(cachedNodeFrame, cachedRelFrame)
  }

  def main(args: Array[String]): Unit = {
    init()

    val graphSize = GraphSize.get()

    val stdGraph = createStdPropertyGraphFromNeo(graphSize)
    val sdfGraph = createSimpleDataFrameGraph(graphSize)
    val neoGraph = new Neo4jViaDriverGraph(GraphDatabase.driver("bolt://localhost:7687", AuthTokens.basic("neo4j", Neo4jPassword.get())))
    println("Graph(s) created!")

    val query = SimplePatternIds(IndexedSeq("Group"), IndexedSeq("ALLOWED_INHERIT"), IndexedSeq("Company"))

    val frameResult = BenchmarkSeries.run(DataFrameBenchmarks(query) -> sdfGraph)
    val rddResult = BenchmarkSeries.run(RDDBenchmarks(query) -> stdGraph)
    val neoResult = BenchmarkSeries.run(Neo4jViaDriverBenchmarks(query) -> neoGraph)
    val cosResult = BenchmarkSeries.run(CypherOnSparkBenchmarks(query) -> stdGraph)
    val results = Seq(frameResult, rddResult, neoResult, cosResult)

    println(BenchmarkSummary(query.toString, sdfGraph.nodes.count(), sdfGraph.relationships.count()))
    println(s"(using parallelism ${sparkSession.sparkContext.defaultParallelism})")

    results.foreach { result =>
      println(s">>>>> Plan for ${result.name}")
      println(result.plan)
      println(s"<<<<< Plan for ${result.name}")
    }
    results.foreach(println)
  }
}