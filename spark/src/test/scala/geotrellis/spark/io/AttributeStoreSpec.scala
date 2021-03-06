package geotrellis.spark.io

import geotrellis.spark._
import geotrellis.spark.testfiles._
import geotrellis.spark.summary._
import geotrellis.raster.io._
import geotrellis.raster.histogram._

import org.scalatest._
import spray.json._
import spray.json.DefaultJsonProtocol._

abstract class AttributeStoreSpec
    extends FunSpec
    with Matchers
    with TestEnvironment
    with TestFiles {
  def attributeStore: AttributeStore

  it("should write to an attribute store") {
    attributeStore.write(LayerId("test1", 1), "test-att1", "test")
    attributeStore.write(LayerId("test2", 2), "test-att1", "test")
    attributeStore.write(LayerId("test2", 2), "test-att2", "test")
    attributeStore.write(LayerId("test3", 3), "test-att1", "test")
  }

  it("should know that these new IDs exist") {
    attributeStore.layerExists(LayerId("test1", 1)) should be (true)
    attributeStore.layerExists(LayerId("test2", 2)) should be (true)
    attributeStore.layerExists(LayerId("test3", 3)) should be (true)
  }

  it("should read layer ids") {
    attributeStore.layerIds.sortBy(_.zoom).toList should be (List(LayerId("test1", 1), LayerId("test2", 2), LayerId("test3", 3)))
  }

  it("should clear out the attribute store") {
    attributeStore
      .layerIds
      .foreach(attributeStore.delete(_))

    attributeStore
      .layerIds.isEmpty should be (true)
  }

  it("should save and pull out a histogram") {
    val layerId = LayerId("test", 3)
    val histo = DecreasingTestFile.histogram

    attributeStore.write(layerId, "histogram", histo)

    val loaded = attributeStore.read[Histogram[Int]](layerId, "histogram")
    loaded.mean should be (histo.mean)
  }

  it("should save and load a random RootJsonReadable object") {
    val layerId = LayerId("test", 3)
    case class Foo(x: Int, y: String)

    implicit object FooFormat extends RootJsonFormat[Foo] {
      def write(obj: Foo): JsObject =
        JsObject(
          "the_first_field" -> JsNumber(obj.x),
          "the_second_field" -> JsString(obj.y)
        )

      def read(json: JsValue): Foo =
        json.asJsObject.getFields("the_first_field", "the_second_field") match {
          case Seq(JsNumber(x), JsString(y)) =>
            Foo(x.toInt, y)
          case _ =>
            throw new DeserializationException(s"JSON reading failed.")
        }
    }

    val foo = Foo(1, "thing")

    attributeStore.write(layerId, "foo", foo)
    attributeStore.read[Foo](layerId, "foo") should be (foo)
  }
}
