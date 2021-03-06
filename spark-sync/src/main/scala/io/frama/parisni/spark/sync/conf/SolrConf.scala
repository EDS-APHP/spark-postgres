package io.frama.parisni.spark.sync.conf

import java.util
import java.util.Optional

import org.apache.solr.client.solrj.impl.CloudSolrClient
import org.apache.solr.client.solrj.request.QueryRequest
import org.apache.solr.common.params.{
  CollectionParams,
  CoreAdminParams,
  ModifiableSolrParams
}
import org.apache.spark.sql.types.IntegerType
import org.apache.spark.sql.{DataFrame, SaveMode, SparkSession}

class SolrConf(
    config: Map[String, String],
    dates: List[String],
    pks: List[String]
) extends SourceAndTarget {

  checkTargetParams(config)
  checkSourceParams(config)

  // SourceTable fields & methods
  val ZKHOST: String =
    "ZKHOST" // ZKHOST & Collection are used by Solr for connexion
  def getZkHost: Option[String] = config.get(ZKHOST)

  def readSource(
      spark: SparkSession,
      zkhost: String,
      collection: String,
      sDateField: String,
      dateMax: String,
      loadType: String = "full"
  ): DataFrame = {

    import org.apache.spark.sql.functions._
    try {
      logger.warn("Reading data from Solr collection---------")
      var dfSolr = spark.emptyDataFrame

      if (!checkCollectionExists(collection, zkhost)) {
        logger.warn(s"Collection ${collection} doesn't exist !!")
        return spark.emptyDataFrame
      }

      val options = Map("collection" -> collection, "zkhost" -> zkhost)
      dfSolr = spark.read.format("solr").options(options).load
      logger.warn("Full Solr DataFrame")
      dfSolr.show()

      if (loadType != "full" && dateMax != "")
        dfSolr = dfSolr.filter(f"${sDateField} >= '${dateMax}'")

      //change "id" type to Integer (Solr uses "id" as String)
      //val dfSolr2 = dfSolr.selectExpr("cast(id as int) id", "make")
      val dfSolr2 = dfSolr.withColumn("id", col("id").cast(IntegerType))

      logger.warn("Solr DataFrame after filter DateMax")
      dfSolr2
    } catch {
      case re: RuntimeException => throw re
      case e: Exception         => throw new RuntimeException(e)
    }
  }

  override def getSourceTableName = config.get(S_TABLE_NAME)

  override def getSourceTableType = config.get(S_TABLE_TYPE)

  override def getSourceDateField = config.get(S_DATE_FIELD)

  def getSourcePK = pks

  // TargetTable methods
  override def getTargetTableName = config.get(T_TABLE_NAME)

  override def getTargetTableType = config.get(T_TABLE_TYPE)

  override def getLoadType = config.get(T_LOAD_TYPE)

  def getDateFields = dates

  override def getDateMax(spark: SparkSession): String = {

    val result = config.get(T_DATE_MAX) match {
      case Some("") =>
        if (
          !checkCollectionExists(
            getTargetTableName.getOrElse(""),
            getZkHost.getOrElse("")
          )
        ) ""
        else
          calculDateMax(
            spark,
            getZkHost.getOrElse(""),
            getTargetTableType.getOrElse(""),
            getTargetTableName.getOrElse(""),
            getDateFields
          )
      case Some(_) => config.get(T_DATE_MAX).get
      case None    => ""
    }
    logger.warn(s"getting the maxdate : ${result}")
    result

  }

  // Write to Solr Collection
  def writeSource(
      sDf: DataFrame,
      zkhost: String,
      collection: String,
      loadType: String = "full"
  ): Unit = {

    try {
      logger.warn("Writing data into Solr collection---------")

      // Initiate a cloud solr client
      implicit val solrClient: CloudSolrClient =
        new CloudSolrClient.Builder(
          util.Arrays.asList(zkhost),
          Optional.empty()
        ).build

      if (!checkCollectionExists(collection, zkhost)) {
        logger.warn(s"Creating solr collection ${collection} from scratch")

        val modParams = new ModifiableSolrParams()
        modParams.set(
          CoreAdminParams.ACTION,
          CollectionParams.CollectionAction.CREATE.name
        )
        modParams.set("name", collection)
        modParams.set("numShards", 1)
        val request: QueryRequest = new QueryRequest(modParams)
        request.setPath("/admin/collections")
        solrClient.request(request)

        //Explicit commit
        solrClient.commit(collection)
      }

      val options = Map(
        "collection" -> collection,
        "zkhost" -> zkhost,
        "commit_within" -> "5000"
      ) //, "soft_commit_secs"-> "10")
      sDf.write
        .format("solr")
        .options(options)
        .mode(SaveMode.Overwrite)
        .save

    } catch {
      case re: RuntimeException => throw re
      case e: Exception         => throw new RuntimeException(e)
    }
  }

  def checkCollectionExists(collection: String, zkhost: String): Boolean = {

    implicit val solrClient: CloudSolrClient =
      new CloudSolrClient.Builder(
        util.Arrays.asList(zkhost),
        Optional.empty()
      ).build

    solrClient.getZkStateReader.getClusterState.hasCollection(collection)
  }
}
