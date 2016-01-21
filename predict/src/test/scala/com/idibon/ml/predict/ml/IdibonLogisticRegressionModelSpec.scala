package com.idibon.ml.predict.ml

import java.io._

import com.idibon.ml.alloy.{ScalaJarAlloy, IntentAlloy}
import com.idibon.ml.feature.indexer.IndexTransformer
import com.idibon.ml.feature.tokenizer.TokenTransformer
import com.idibon.ml.feature.{ContentExtractor, FeaturePipelineBuilder, FeaturePipeline}
import com.idibon.ml.feature.language.LanguageDetector
import com.idibon.ml.predict.{SingleLabelDocumentResult, PredictOptionsBuilder, PredictModel}
import org.apache.spark.ml.classification.IdibonSparkLogisticRegressionModelWrapper
import org.apache.spark.mllib.linalg.{Vector, Vectors}
import org.json4s._
import org.scalatest.{ParallelTestExecution, BeforeAndAfter, Matchers, FunSpec}
import org.json4s._
import org.json4s.JsonDSL._
import scala.collection.mutable

/**
  * Class to test IdibonLogisticRegressionModel & related.
  */
class IdibonLogisticRegressionModelSpec extends FunSpec
with Matchers with BeforeAndAfter with ParallelTestExecution {

  val pipeline: FeaturePipeline = (FeaturePipelineBuilder.named("StefansPipeline")
    += (FeaturePipelineBuilder.entry("convertToIndex", new IndexTransformer, "convertToTokens"))
    += (FeaturePipelineBuilder.entry("convertToTokens", new TokenTransformer, "contentExtractor", "language"))
    += (FeaturePipelineBuilder.entry("language", new LanguageDetector, "$document"))
    += (FeaturePipelineBuilder.entry("contentExtractor", new ContentExtractor, "$document"))
    := ("convertToIndex"))

  val text: String = "Everybody loves replacing hadoop with spark because it's much faster. a b d"
  val doc: JObject = ( "content" -> text )
  pipeline.prime(List(doc))

  var tempFilename = ""
  before {
    tempFilename = ""
  }

  after {
    try {
      new File(tempFilename).delete()
    } catch {
      case ioe: IOException => None
    }
  }

  describe("Save & load integration test") {
    it("saves, loads and predicts as expected") {
      val intercept = -1.123
      val coefficients = pipeline(doc)
      val label: String = "alabel"
      // do hacky thing where model coefficients are document coefficients -- just to make
      // sure it all works
      val model = new IdibonLogisticRegressionModel(
        label,
        new IdibonSparkLogisticRegressionModelWrapper(label, coefficients, intercept),
        pipeline)
      val labelToModel = Map(("alabel" -> model))
      val alloy = new ScalaJarAlloy(labelToModel, Map[String, String]())
      tempFilename = "save.jar"
      alloy.save(tempFilename)
      // let's make sure to delete the file on exit
      val jarFile = new File(tempFilename)
      jarFile.exists() shouldBe true
      // get alloy back & predict on it.
      val resurrectedAlloy = ScalaJarAlloy.load(null, tempFilename)
      val options = new PredictOptionsBuilder().build()
      val result1 = alloy.predict(doc, options).get(label).asInstanceOf[SingleLabelDocumentResult]
      val result2 = resurrectedAlloy.predict(doc, options).get(label).asInstanceOf[SingleLabelDocumentResult]
      result2.matchCount shouldBe result1.matchCount
      result2.probability shouldBe result1.probability
    }
  }

  describe("Loads as intended") {
    it("Throws exception on unhandled version") {
      intercept[IOException] {
        new IdibonLogisticRegressionModelLoader().load(null,
          null,  Some(JObject(List[JField](
            ("version", JString("0.0.0")), ("label", JString("alabel"))))))
      }
    }
  }

  describe("Saves as intended") {
    it("returns config as expected") {
      val alloy = new IntentAlloy()
      val intercept = -1.123
      val coefficients = pipeline(doc)
      val label: String = "alabel"
      val model = new IdibonLogisticRegressionModel(
        label,
        new IdibonSparkLogisticRegressionModelWrapper(label, coefficients, intercept),
        pipeline)
      val config = model.save(alloy.writer())
      implicit val formats = DefaultFormats
      val actualLabel = (config.get \ "label" ).extract[String]
      actualLabel shouldEqual label
      val featureMeta = (config.get \ "feature-meta").extract[JObject]
      featureMeta should not be JNothing
      featureMeta should not be JNull
    }
  }

  describe("Tests predict") {
    it("returns appropriate probability") {
      val intercept = -10.123
      val coefficients = pipeline(doc)
      val label: String = "alabel"
      val model = new IdibonLogisticRegressionModel(
        label,
        new IdibonSparkLogisticRegressionModelWrapper(label, coefficients, intercept),
        pipeline)
      val result = model.predict(doc, new PredictOptionsBuilder().build()).asInstanceOf[SingleLabelDocumentResult]
      result.probability shouldBe 0.9999999f
      result.matchCount shouldBe 1
    }
    it("should return significant features") {
      //TODO:
    }
  }

  describe("Test writeCodecLibSVM") {
    it("test sparse vector"){
      val out: ByteArrayOutputStream = new ByteArrayOutputStream()
      val intercept = -1.123
      val coefficients = Vectors.sparse(
        5, Array[Int](1, 10, 100, 1000, 10000), Array[Double](0.234, 1.23, 234234.234, 64556.034, 2.0))
      val uid = "thisIsAUID"
      IdibonLogisticRegressionModel.writeCodecLibSVM(
        new WrappedByteArrayOutputStream(out), intercept, coefficients, uid)
      out.toByteArray() shouldEqual Array[Byte](10, 116, 104, 105, 115, 73, 115, 65, 85, 73, 68,
        -65, -15, -9, -50, -39, 22, -121, 43, 5, 5, 1, 63, -51, -13, -74, 69, -95, -54, -63, 10,
        63, -13, -82, 20, 122, -31, 71, -82, 100, 65, 12, -105, -47, -33, 59, 100, 90, -24, 7, 64,
        -17, -123, -127, 22, -121, 43, 2, -112, 78, 64, 0, 0, 0, 0, 0, 0, 0)
      out.close()
    }

    it("test empty sparse vector"){
      val out: ByteArrayOutputStream = new ByteArrayOutputStream()
      val intercept = 0.50
      val coefficients = Vectors.sparse(0, Array[Int](), Array[Double]())
      val uid = "a"
      IdibonLogisticRegressionModel.writeCodecLibSVM(
        new WrappedByteArrayOutputStream(out), intercept, coefficients, uid)
      out.toByteArray() shouldEqual Array[Byte](1, 97, 63, -32, 0, 0, 0, 0, 0, 0, 0, 0)
      out.close()
    }
  }

  describe("Test readCodecLibSVM") {
    it("works on sparse vector") {
      val input = new ByteArrayInputStream(Array[Byte](10, 116, 104, 105, 115, 73, 115, 65, 85, 73, 68,
        -65, -15, -9, -50, -39, 22, -121, 43, 5, 5, 1, 63, -51, -13, -74, 69, -95, -54, -63, 10,
        63, -13, -82, 20, 122, -31, 71, -82, 100, 65, 12, -105, -47, -33, 59, 100, 90, -24, 7, 64,
        -17, -123, -127, 22, -121, 43, 2, -112, 78, 64, 0, 0, 0, 0, 0, 0, 0))
      val (intercept: Double,
      coefficients: Vector,
      uid: String) = IdibonLogisticRegressionModel.readCodecLibSVM(new WrappedByteArrayInputStream(input))
      intercept shouldEqual -1.123
      coefficients shouldEqual  Vectors.sparse(
        5, Array[Int](1, 10, 100, 1000, 10000), Array[Double](0.234, 1.23, 234234.234, 64556.034, 2.0))
      uid shouldEqual "thisIsAUID"
    }

    it("works on empty sparse vector") {
      val input = new ByteArrayInputStream(Array[Byte](1, 97, 63, -32, 0, 0, 0, 0, 0, 0, 0, 0))
      val (intercept: Double,
      coefficients: Vector,
      uid: String) = IdibonLogisticRegressionModel.readCodecLibSVM(new WrappedByteArrayInputStream(input))
      intercept shouldEqual 0.50
      coefficients shouldEqual Vectors.sparse(0, Array[Int](), Array[Double]())
      uid shouldEqual "a"
    }
  }
}

private class WrappedByteArrayOutputStream(baos: ByteArrayOutputStream) extends DataOutputStream(baos)
private class WrappedByteArrayInputStream(bais: ByteArrayInputStream) extends DataInputStream(bais)
