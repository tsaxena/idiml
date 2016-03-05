package com.idibon.ml.train.alloy

import com.idibon.ml.alloy.{HasTrainingSummary, BaseAlloy, Alloy}
import com.idibon.ml.common.Engine
import com.idibon.ml.predict.Classification
import com.idibon.ml.predict.ml.TrainingSummary
import com.idibon.ml.predict.ml.metrics.{MetricClass, Metric}
import com.typesafe.scalalogging.StrictLogging
import org.json4s.JsonAST.JObject

/**
  * Cross validating alloy trainer.
  *
  * Given, a data set, a portion and a trainer, performs alloy level
  * cross validation and then trains an alloy over all the data.
  *
  * This facilitates getting an idea how a trained alloy will perform
  * on unseen data.
  *
  * The returned alloy contains averaged training summaries from the cross
  * validation, as well as the training metrics from fitting over all the
  * data.
  *
  * Note: the training metrics should be better than the averaged
  * ones, since we're evaluating using the training data, while the
  * averaged metrics were based on the average of the fold hold out sets.
  *
  * @author "Stefan Krawczyk <stefan@idibon.com>" on 3/2/16.
  * @param engine
  * @param trainer
  * @param numFolds
  * @param portion
  * @param foldSeed
  */
class CrossValidatingAlloyTrainer(engine: Engine,
                                  trainer: AlloyTrainer,
                                  numFolds: Int,
                                  portion: Double,
                                  foldSeed: Long,
                                  skipFinalTraining: Boolean = false)
  extends AlloyTrainer with KFoldDataSetCreator with StrictLogging {

  /**
    *
    * @param builder
    * @return
    */
  def this(builder: CrossValidatingAlloyTrainerBuilder) = this(
    builder.engine,
    builder.trainerBuilder.build(builder.engine),
    builder.numFolds,
    builder.portion,
    builder.foldSeed)
  /** Trains a model and generates an Alloy from it
    *
    * Callers must provide a callback function which returns a traversable
    * list of documents; this function will be called multiple times, and
    * each invocation of the function must return an instance that will
    * traverse over the exact set of documents traversed by previous instances.
    *
    * Traversed documents should match the format generated by
    * idibin.git:/idibin/bin/open_source_integration/export_training_to_idiml.rb
    *
    * { "content": "Who drives a chevy maliby Would you recommend it?
    * "metadata": { "iso_639_1": "en" },
    * "annotations: [{ "label": { "name": "Intent" }, "isPositive": true }]}
    *
    * @param name          - a user-friendly name for the Alloy
    * @param docs          - a callback function returning a traversable sequence
    *                      of JSON training documents, such as those generated by export_training_to_idiml.rb
    * @param labelsAndRules a callback function returning a traversable sequence
    *                      of JSON Config. Should only be one line,   generated by export_training_to_idiml.rb.
    * @param config        training configuration parameters. Optional.
    * @return an Alloy with the trained model
    */
  override def trainAlloy(name: String,
                          docs: () => TraversableOnce[JObject],
                          labelsAndRules: JObject,
                          config: Option[JObject]): Alloy[Classification] = {
    implicit val formats = org.json4s.DefaultFormats
    // Create uuidsByLabel
    val uuidTolabel = (labelsAndRules \ "uuid_to_label").extract[JObject]
    val labels = uuidToLabelGenerator(uuidTolabel)
    val labelToDouble = labels.zipWithIndex.map({ case (label, index) => (label, index.toDouble) }).toMap

    // create evaluator to know how to look at results at the alloy level
    val taskType = (labelsAndRules \ "task_type").extract[String]
    val trainingSummaryCreator: TrainingSummaryCreator = getTrainingSummaryCreator(taskType, labels.size)

    // create folds -- i.e. data sets
    val folds = createFoldDataSets(docs, numFolds, portion, foldSeed, labelToDouble)
    // get training summaries from alloy
    val summaries = crossValidate(name, labelsAndRules, config, trainingSummaryCreator, folds)
    // average the results
    val resultsAverage = averageMetrics(s"$name${CrossValidatingAlloyTrainer.SUFFIX}", summaries)
    logger.info(s"Xval results for - $name:\n ${resultsAverage.toString}")
    if (skipFinalTraining) {
      // create new alloy with just xval summaries -- no model!
      new BaseAlloy[Classification](name, labels, Map()) with HasTrainingSummary {
        override def getTrainingSummaries = {
          Some(Seq(resultsAverage))
        }
      }
    } else {
      // train on all data
      val finalAlloy = trainer.trainAlloy(name, docs, labelsAndRules, config)
        .asInstanceOf[BaseAlloy[Classification] with HasTrainingSummary]
      // get training summary
      val finalAlloyTrainingSummary = HasTrainingSummary.getSummaries[Classification](finalAlloy)
        .map(ts => new TrainingSummary(s"$name-${ts.identifier}", ts.metrics))
      // create new alloy with combined summaries
      new BaseAlloy[Classification](name, labels, finalAlloy.models) with HasTrainingSummary {
        override def getTrainingSummaries = {
          Some(Seq(resultsAverage) ++ finalAlloyTrainingSummary)
        }
      }
    }
  }

  /**
    * Method that goes over the folds and delegates training & evaluation.
    *
    * @param name
    * @param labelsAndRules
    * @param config
    * @param trainingSummaryCreator
    * @param folds
    * @return
    */
  def crossValidate(name: String,
                    labelsAndRules: JObject,
                    config: Option[JObject],
                    trainingSummaryCreator: TrainingSummaryCreator,
                    folds: Seq[TrainingDataSet]): Seq[TrainingSummary] = {
    folds.par.map(ds => {
      val foldName = s"$name-${ds.info.fold}-${ds.info.portion}"
      val tawe = new TrainAlloyWithEvaluation(foldName, engine, trainer, ds, trainingSummaryCreator)
      tawe(labelsAndRules, config)
    }).toList.toSeq
  }

  /**
    * Creates appropriate class to know how to evaluate multi-label vs multi-class case.
    *
    * @param taskType the task type to tell us multi-label vs multi-class
    * @param numLabels the number of labels for this alloy.
    * @return
    */
  def getTrainingSummaryCreator(taskType: String, numLabels: Int): TrainingSummaryCreator = {
    taskType match {
      case AlloyTrainer.DOCUMENT_MUTUALLY_EXCLUSIVE => {
        val defaultThreshold = 1.0f / numLabels.toFloat // this will be multinomial or k-binary
        new MultiClassMetricsEvaluator(defaultThreshold)
      }
      case AlloyTrainer.DOCUMENT_MULTI_LABEL => {
        val defaultThreshold = 0.5f // this will be k-binary always
        new MultiLabelMetricsEvaluator(defaultThreshold)
      }
    }
  }

  /**
    * Function to average metrics based on metric type.
    *
    * @param summaryName the name to give the new training summary.
    * @param trainingSummaries the training summaries to average metrics for.
    * @return a single training summary with averaged metrics.
    */
  def averageMetrics(summaryName: String, trainingSummaries: Seq[TrainingSummary]):
  TrainingSummary = {
    val allMetrics = trainingSummaries.flatMap(ts => ts.metrics)
    val groupedMetrics = allMetrics.groupBy(m => m.metricType)
    val averagedMetrics = groupedMetrics.flatMap({ case (metricType, metrics) =>
      Metric.average(metrics, Some(MetricClass.Alloy))
    }).toSeq
    new TrainingSummary(summaryName, averagedMetrics)
  }
}

object CrossValidatingAlloyTrainer {
  val SUFFIX = "-AVG"
}
