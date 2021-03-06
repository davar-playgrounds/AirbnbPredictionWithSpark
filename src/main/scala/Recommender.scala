import org.apache.spark.sql.{DataFrame, SparkSession}
import java.io.File
import java.nio.file.{Files, Paths}
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

import org.apache.commons.io.FileUtils
import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.types._
import org.apache.spark.sql.{DataFrame, Row, SparkSession}
import org.apache.spark.sql.functions.{col, udf}
import org.apache.spark.mllib.recommendation.{ALS, MatrixFactorizationModel, Rating}
object Recommender {

  def getRating(sparkSession: SparkSession, listingsDf : DataFrame, neibourhoodDf : DataFrame, reviewsDetailDf : DataFrame): RDD[Rating] ={
    import sparkSession.implicits._

//    neibourhoodDf.show(5)
//      +--------------+----------------+
//      | neighbourhood|neighbourhood_id|
//      +--------------+----------------+
//      |     Adachi Ku|               0|
//      |   Akiruno Shi|               1|
//      |  Akishima Shi|               2|
//      |Aogashima Mura|               3|
//      |    Arakawa Ku|               4|
//      +--------------+----------------+
//    only showing top 5 rows

//    listingsDf.show(5)
//    +------+-------+-------------------+-------------+
//    |    id|host_id|          host_name|neighbourhood|
//    +------+-------+-------------------+-------------+
//    | 35303| 151977|             Miyuki|   Shibuya Ku|
//    |197677| 964081|    Yoshimi & Marek|    Sumida Ku|
//    |289597| 341577|           Hide&Kei|    Nerima Ku|
//    |370759|1573631|Gilles,Mayumi,Taiki|  Setagaya Ku|
//    |700253| 341577|           Hide&Kei|    Nerima Ku|
//    +------+-------+-------------------+-------------+
//    only showing top 5 rows
    // listings
    val joinedListingNeighbourDf = listingsDf
      .join(neibourhoodDf, col(colName = "listingsDf.neighbourhood") === col(colName = "neigbourhoodsDf.neighbourhood"),joinType = "inner")
      .drop(col(colName = "neigbourhoodsDf.neighbourhood"))
      .as(alias = "joinedListingNeighbourDf")

    joinedListingNeighbourDf.show(5)

//      +------+-------+-------------------+-------------+----------------+
//      |    id|host_id|          host_name|neighbourhood|neighbourhood_id|
//      +------+-------+-------------------+-------------+----------------+
//      | 35303| 151977|             Miyuki|   Shibuya Ku|              52|
//      |197677| 964081|    Yoshimi & Marek|    Sumida Ku|              56|
//      |289597| 341577|           Hide&Kei|    Nerima Ku|              43|
//      |370759|1573631|Gilles,Mayumi,Taiki|  Setagaya Ku|              51|
//      |700253| 341577|           Hide&Kei|    Nerima Ku|              43|
//      +------+-------+-------------------+-------------+----------------+
//    only showing top 5 rows
//    print(reviewsDetailDf.printSchema())
    val joinedListingReviewsDf = joinedListingNeighbourDf
      .join(reviewsDetailDf, col("joinedListingNeighbourDf.id") === col("reviewsDetailDf.listing_id"), "inner")
      .drop("id")
      .as("joinedListingReviewsDf")
    joinedListingReviewsDf.show(5)
//    println(s">> joinedListingReviewsDf count: ${joinedListingReviewsDf.count()}")
//      +--------+---------+-------------+----------------+----------+----------+-----------+-------------+
//      | host_id|host_name|neighbourhood|neighbourhood_id|listing_id|      date|reviewer_id|reviewer_name|
//      +--------+---------+-------------+----------------+----------+----------+-----------+-------------+
//      |19152993|      Sei|      Kita Ku|              24|   4888140|2015-02-23|   27196217|      Sujitra|
//      |19152993|      Sei|      Kita Ku|              24|   4888140|2015-02-27|   24716396|      Michael|
//      |19152993|      Sei|      Kita Ku|              24|   4888140|2015-03-20|   27693465|        Cyrus|
//      |19152993|      Sei|      Kita Ku|              24|   4888140|2015-03-30|   25040486|     Angelica|
//      |19152993|      Sei|      Kita Ku|              24|   4888140|2015-04-04|   26105293|         Alex|
//      +--------+---------+-------------+----------------+----------+----------+-----------+-------------+
//    only showing top 5 rows

    val rating = joinedListingReviewsDf
      .groupBy("reviewer_id", "reviewer_name", "neighbourhood_id", "neighbourhood")
      .count()
//      +-----------+-------------+----------------+-------------+-----+
//      |reviewer_id|reviewer_name|neighbourhood_id|neighbourhood|count|
//      +-----------+-------------+----------------+-------------+-----+
//      |   31598476|        Louis|              24|      Kita Ku|    1|
//      |   53555222|       Nicole|              24|      Kita Ku|    1|
//      |   75870371|         Binu|              24|      Kita Ku|    1|
//      |   65023918|    Madeleine|              56|    Sumida Ku|    1|

      .rdd
//    rating.foreach(println)
    //      [198398929,Yuan Ching,58,Taito Ku,1]
    //      [179041367,Tomoko,58,Taito Ku,1]
    //      [229644831,美怜,30,Koto Ku,1]
    //      [171545123,Pimon,54,Shinjuku Ku,1]
      .map(r => Rating(
        r.getAs[Int]("reviewer_id"),
        r.getAs[Long]("neighbourhood_id").toInt,
        r.getLong(4).toDouble
      ))

    rating

    /*
    public Rating(int user,
      int product,
      double rating)
     */
//    rating.foreach(println)

//    Rating(274610355,60,1.0)
//    Rating(251744618,22,1.0)
//    Rating(163114806,36,1.0)
//    Rating(12385578,54,1.0)
  }

  def trainModel(sparkSession: SparkSession, rating: RDD[Rating], numIterations: Int, path: String) = {
    // val Array(training, test) = rating.randomSplit(Array(0.8, 0.2))

    // Build the recommendation model using ALS
    val rank = 10
    val model = ALS.train(rating, rank, numIterations, 0.01)

    // Evaluate the model on rating data
    val usersProducts = rating
      .map { case Rating(user, product, rate) => (user, product)}

    val predictions = model
      .predict(usersProducts)
      .map { case Rating(user, product, rate) => ((user, product), rate)}

    val ratesAndPreds = rating
      .map { case Rating(user, product, rate) => ((user, product), rate)}
      .join(predictions)

    val MSE = ratesAndPreds
      .map { case ((user, product), (r1, r2)) =>
        val err = (r1 - r2)
        err * err
      }
      .mean()

    if (Files.exists(Paths.get(path))) {
      FileUtils.deleteQuietly(new File(path))
    }

    model.save(sparkSession.sparkContext, path)
    MSE
  }

  def loadModel(sparkSession: SparkSession, path: String): MatrixFactorizationModel =
    MatrixFactorizationModel.load(sparkSession.sparkContext, path)

  def getRecommendations(spark: SparkSession, model: MatrixFactorizationModel, products: Int,
                         reviewerMap: Map[Long, String], neighbourhoodMap: Map[Long, String]): DataFrame = {
    val recommendationsRdd = model
      .recommendProductsForUsers(products)
      .map(r => {
        val reviewerId = r._1.toInt
        val reviewerName = reviewerMap.getOrElse(reviewerId.toLong, "empty")
        val neighbourhoodNames = r._2.map(rating => neighbourhoodMap.getOrElse(rating.product.toLong, "empty")).toList
        Row(reviewerId, reviewerName, neighbourhoodNames)
      })

    val schema = new StructType()
      .add(StructField("reviewerId", IntegerType, true))
      .add(StructField("reviewerName", StringType, true))
      .add(StructField("neighbourhoodNames", ArrayType(StringType), true))

    val nowDatetimeUdf = udf(() => DateTimeFormatter.ofPattern("yyyy-MM-dd").format(LocalDateTime.now))

    val recommendationsDf = spark
      .createDataFrame(recommendationsRdd, schema)
      .withColumn("date", nowDatetimeUdf())

    recommendationsDf.show(5)
    println(recommendationsDf.count())
    recommendationsDf

  }
}
