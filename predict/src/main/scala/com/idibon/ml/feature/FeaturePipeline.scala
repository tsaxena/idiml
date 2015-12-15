import scala.collection.mutable.{HashSet => MutableSet, MutableList}

import org.json4s._
import org.json4s.Formats._
import org.apache.spark.mllib.linalg.Vector
import com.idibon.ml.alloy.Alloy

package com.idibon.ml.feature {

  /** The feature pipeline transforms documents into feature vectors
    */
  class FeaturePipeline extends Archivable {
    type AnyTransformer = FeatureTransformer[Feature[_]]

    /** Applies the entire feature pipeline to the provided document.
      *
      * Returns a sequence of Vectors, one for each FeatureSpace included
      * in this pipeline.
      */
    def apply(document: JObject): Seq[Vector] = {
      List[Vector]()
    }

    def save(resourceWriter: Alloy.Writer): Option[JObject] = {
      None
    }

    /** Load the FeaturePipeline from the Alloy
      *
      * See {@link com.idibon.ml.feature.FeaturePipeline}
      *
      * @param reader    an Alloy.Reader configured for loading all of the
      *   resources for this FeaturePipeline
      * @param config    the JSON configuration object generated when this
      *   pipeline was last saved
      */
    def load(reader: Alloy.Reader, config: Option[JObject]): this.type = {
      this
    }

    /** Orders the transformers in a pipeline in dependency order
      *
      * Orders all of the entries in a pipeline based on the order that
      * the transform must be applied to satisfy the defined input
      * dependencies, then splits the sorted list into variable-sized
      * stages of independent processing (i.e., any entry in stage N
      * depends only on the results of transforms in [Stage 0..N-1])
      */
    private[feature] def sortDependencies(pipeline: List[PipelineEntry]):
        List[List[String]] = {

      /* recursively construct the full dependency graph using the partial
       * results of previously-calculated dependencies. results in a table
       * (name -> (List(direct dependencies), Set(all dependencies)) */
      val dependencies = pipeline.map(entry => {
        (entry.name -> (entry.inputs, MutableSet[String]()))
      }).toMap

      def getDeps(name: String, history: MutableSet[String]): Set[String] = {
        if (history.contains(name)) {
          /* the graph is already processed for this node, so just return
           * an immutable copy of the previously-calculated results */
          dependencies(name)._2.toSet
        } else {
          // calculate transitive dependencies for this entry, if it exists
          dependencies.get(name).flatMap({
            case (named: List[String], transitive: MutableSet[String]) => {
              /* add all of this node's direct dependencies to the set of
               * all transitive dependencies */
              transitive ++= named
              /* add the current object to the history list, to prevent
               * infinite recursion in case of cycles */
              history += name
              /* add all of the computed dependencies for each direct
               * dependency to the dependency list */
              transitive ++= named.map(n => getDeps(n, history)).reduce(_ ++ _)
              // nodes should never appear as their own dependencies
              if (transitive.contains(name))
                throw new IllegalArgumentException("Cyclic graph: " +name)
              Some(transitive)
            }
          }) match {
            // return an immutable copy of the node's transitive dependencies
            case Some(x) => x.toSet
            // base case handling for initial document input
            case None if name == FeaturePipeline.DocumentInput => Set()
            // or throw an exception if the node doesn't exist
            case _ => throw new IllegalArgumentException("Missing: " + name)
          }
        }
      }

      val graphed = MutableSet[String]()
      pipeline.foreach(entry => getDeps(entry.name, graphed))

      /* sort the pipeline stages based on the order that they must be
       * processed to fulfill the dependency graph: returns true if A
       * is a dependency of B, or if A has fewer total dependencies than B. */
      val sorted = graphed.toList.sortWith((a, b) => {
        dependencies(b)._2.contains(a) ||
        dependencies(a)._2.size <= dependencies(b)._2.size
      })

      val stages = MutableList[List[String]]()
      val currentStage = MutableSet[String]()

      sorted.foreach(entry => {
        if (dependencies(entry)._1.exists(currentStage.contains(_))) {
          /* if any of the named dependencies for the transform exist
           * within the current stage, then a new stage must be created
           * for the transform */
          stages += currentStage.toList
          currentStage.clear
        }
        currentStage += entry
      })
      // add the final stage (should be $output, in well-formed graphs)
      if (!currentStage.isEmpty) stages += currentStage.toList
      stages.toList
    }

    /* master table of all FeatureTransformers in the pipeline */
    private var transforms: Option[Map[String, AnyTransformer]] = None
  }

  private[feature] object FeaturePipeline {
    val DocumentInput = "$document"
  }

  // Schema for each entry within the pipeline JSON array
  private[feature] case class PipelineEntry(name: String, inputs: List[String])
}
