package io.frama.parisni.spark.hive

import com.typesafe.scalalogging.LazyLogging
import org.apache.spark.sql.{SaveMode, SparkSession}

/**
  * This application copies and overwrites all
  * the tables from one database into the other
  */
object CopyTablesToOtherDatabase extends App with LazyLogging {

  val Array(fromDb: String, toDb: String, format: String) = args match {
    case Array(_, _)    => (args(0), args(1), "parquet")
    case Array(_, _, _) => args
    case _ =>
      throw new UnsupportedOperationException(
        "shall specify fromDb, toDb and optionally the format")
  }

  implicit val spark = SparkSession
    .builder()
    .appName("")
    .enableHiveSupport()
    .getOrCreate()

  copyAllTables(fromDb, toDb, format)

  def copyAllTables(fromDb: String, toDb: String, format: String)(
      implicit spark: SparkSession) = {
    for {
      table <- listTables(fromDb)
      if !table.contains("_tmp")
    }(
      copyTable(fromDb, toDb, table, format)(spark)
    )
  }

  def listTables(fromDb: String)(implicit ss: SparkSession) = {
    val tables: Array[String] =
      ss.catalog.listTables(fromDb).collect().map(table => table.name)
    logger.info(s"Listed ${tables.size} tables to copy")
    tables
  }

  def copyTable(fromDb: String,
                toDb: String,
                table: String,
                format: String = "parquet")(implicit ss: SparkSession) = {
    logger.info(s"Copying ${fromDb}.${table} TO ${toDb}.${table}")
    val copyTable = ss.table(s"${fromDb}.${table}")
    copyTable.write
      .format(format)
      .mode(SaveMode.Overwrite)
      .saveAsTable(s"${toDb}.${table}")
  }

}