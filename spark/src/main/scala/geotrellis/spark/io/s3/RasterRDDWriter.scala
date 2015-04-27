package geotrellis.spark.io.s3

import geotrellis.spark._
import geotrellis.raster._
import geotrellis.spark.io._
import geotrellis.spark.utils.KryoSerializer
import geotrellis.spark.io.index._
import geotrellis.spark.io.s3._
import org.apache.spark.SparkContext
import org.apache.spark.SparkContext._
import java.io.ByteArrayInputStream
import com.amazonaws.services.s3.model.{PutObjectRequest, PutObjectResult}
import com.amazonaws.auth.{AWSCredentialsProvider, BasicAWSCredentials}
import com.amazonaws.services.s3.model.ObjectMetadata
import geotrellis.index.zcurve.Z2
import com.typesafe.scalalogging.slf4j._
import scala.collection.mutable.ArrayBuffer
import com.amazonaws.services.s3.model.AmazonS3Exception
import scala.reflect.ClassTag
import scala.concurrent._
import java.util.concurrent.Executors
import scala.concurrent.duration._

abstract class RasterRDDWriter[K: ClassTag] extends LazyLogging {
  val encodeKey: (K, KeyIndex[K], Int) => String

  def write(
    s3client: ()=>S3Client, 
    bucket: String, 
    layerPath: String,
    keyBounds: KeyBounds[K],
    keyIndex: KeyIndex[K],
    clobber: Boolean)
  (layerId: LayerId, rdd: RasterRDD[K])
  (implicit sc: SparkContext): Unit = {
    // TODO: Check if I am clobbering things        
    logger.info(s"Saving RasterRDD for $layerId to ${layerPath}")
        
    val maxLen = { // lets find out the widest key we can possibly have
      def digits(x: Long): Int = if (x < 10) 1 else 1 + digits(x/10)
      digits(keyIndex.toIndex(keyBounds.maxKey))
    }

    val bcClient = sc.broadcast(s3client)
    val catalogBucket = bucket
    val path = layerPath
    val ek = encodeKey
    rdd
      .foreachPartition { partition =>

        val s3client: S3Client = bcClient.value.apply

        val executors = Executors.newFixedThreadPool(64)
        implicit val ec = ExecutionContext.fromExecutor(executors)

        val futures = partition.map{ row =>
          val index = keyIndex.toIndex(row._1) 
          val bytes = KryoSerializer.serialize[(K, Tile)](row)
          val metadata = new ObjectMetadata()
          metadata.setContentLength(bytes.length);              
          val is = new ByteArrayInputStream(bytes)
          val req = new PutObjectRequest(catalogBucket, s"$path/${ek(row._1, keyIndex, maxLen)}", is, metadata)
          future { blocking { s3client.putObjectWithBackoff(req) } }
        }.toArray

        for (f <- futures){
          f.onFailure{ case e => throw e }
          Await.ready(f, 10 minutes)
        }

        executors.shutdown
      }

    logger.info(s"Finished saving tiles to ${layerPath}")
  }   
}