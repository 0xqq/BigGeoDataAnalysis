package cn.whu.ypfamily.BigGeoDataAnalysis.analyse

import java.util.Date

import cn.whu.ypfamily.BigGeoDataAnalysis.rdd.SpatialRDD
import com.google.gson.{Gson, JsonObject}
import org.apache.hadoop.hbase.HBaseConfiguration
import org.apache.hadoop.hbase.mapreduce.TableInputFormat
import org.apache.spark.{SparkConf, SparkContext}

object OverlayPDT {
  def main(args: Array[String]): Unit = {
    if (args.length < 10 || args.length > 12) {
      println("input " +
        "*<hdfs path> " +
        "*<zookeeper server list> " +
        "*<DLTB table name> " +
        "*<DLTB geometry column family name> " +
        "*<DLTB geometry column name> " +
        "*<PDT table name> " +
        "*<PDT geometry column family name> " +
        "*<PDT geometry column name> " +
        "*<PD tag name> " +
        "*<output path>" +
        "<partition number>")
      return
    }

    // 开始时间
    val startTime = new Date().getTime

    // 获取参数
    val hdfsPath = args(0)
    val serverList = args(1)
    val dltbTableName = args(2)
    val dltbGeoColumnFamily = args(3)
    val dltbGeoColumn = args(4)
    val pdtTableName = args(5)
    val pdtGeoColumnFamily = args(6)
    val pdtGeoColumn = args(7)
    val pdTagName = args(8)
    val outputPath = args(9)

    // 设置Spark参数生成Spark上下文
    val sparkConf = new SparkConf().setAppName("OverlayPDT")
    val sc = new SparkContext(sparkConf)

    // 设置HBase参数
    val hbaseConf = HBaseConfiguration.create()
    hbaseConf.set("fs.defaultFS", hdfsPath)
    hbaseConf.set("hbase.zookeeper.quorum", serverList)
    hbaseConf.set("fs.hdfs.impl", "org.apache.hadoop.hdfs.DistributedFileSystem")

    var rddDLTB: SpatialRDD = null

    // 如果不进行重分区
    if (args.length == 10) {
      // 从HBase读取DLTB数据
      hbaseConf.set(TableInputFormat.INPUT_TABLE, dltbTableName)
      rddDLTB = SpatialRDD.createSpatialRDDFromHBase(sc, hbaseConf, dltbGeoColumnFamily, dltbGeoColumn)
    }

    // 如果进行重分区
    if (args.length == 11) {
      // 获得参数
      val partitionNum = args(10).toInt
      // 从HBase读取DLTB数据
      hbaseConf.set(TableInputFormat.INPUT_TABLE, dltbTableName)
      rddDLTB = SpatialRDD.createSpatialRDDFromHBase(sc, hbaseConf, dltbGeoColumnFamily, dltbGeoColumn, partitionNum)
    }

    // 从HBase读取PDT数据进行广播
    hbaseConf.set(TableInputFormat.INPUT_TABLE, pdtTableName)
    val arrPDT = SpatialRDD.createSpatialRDDFromHBase(sc, hbaseConf, pdtGeoColumnFamily, pdtGeoColumn).collect()
    var bcPDT = sc.broadcast(arrPDT)

    // DLTB叠加PDT
    val rddDLTBwithPDJB = rddDLTB.map(dltb => {
      var pdjb = ""
      var gson = new Gson()
      // 寻找最大重叠面积的坡度级别
      var maxOverlayArea = 0.0
      bcPDT.value.foreach(pdt => {
        val geohashDltb = dltb._1.split("_")(0)
        val geohashPdt = pdt._1.split("_")(0)
        // 如果geohash是包含或相等关系，则两者可以判断为重叠
        if (geohashDltb.indexOf(geohashPdt) != -1 || geohashPdt.indexOf(geohashDltb) != -1) {
          println(geohashDltb + " " + geohashPdt)
          if (dltb._2.geom.getEnvelopeInternal.intersects(pdt._2.geom.getEnvelopeInternal)) {
            val geomIntersect = pdt._2.geom.intersection(pdt._2.geom) // 叠置分析
            if (geomIntersect != null) {
              val overlayArea = geomIntersect.getArea
              println(overlayArea + " " + maxOverlayArea)
              if (overlayArea > maxOverlayArea) {
                maxOverlayArea = overlayArea
                pdjb = gson.fromJson(pdt._2.tags, classOf[JsonObject]).get(pdTagName).getAsString
                println("更新为" + maxOverlayArea + " " + pdjb)
              }
            }
          }
        }
      })
      // 为DLTB添加坡度字段
      val dltbTagObj = gson.fromJson(dltb._2.tags, classOf[JsonObject])
      dltbTagObj.addProperty(pdTagName, pdjb)
      dltb._2.tags = dltbTagObj.toString
    })

    // 输出结果到HDFS
    rddDLTBwithPDJB.saveAsTextFile(outputPath)

    // 关闭Spark上下文
    sc.stop()

    // 结束时间，计算耗时
    val endTime = new Date().getTime
    println("耗时：" + (endTime - startTime) + "毫秒")
  }
}