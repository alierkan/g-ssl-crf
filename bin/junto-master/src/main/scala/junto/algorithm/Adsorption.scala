package junto.algorithm

import junto.config.{Label, Flags}
import junto.eval.GraphEval
import junto.graph._
import junto.util.CollectionUtil
import junto.util.Constants
import junto.util.ProbUtil
import java.util.ArrayList
import java.util.HashMap
import java.util.Iterator
import gnu.trove.map.hash.TObjectDoubleHashMap
import gnu.trove.iterator.TObjectDoubleIterator
import scala.collection.JavaConversions._
import com.typesafe.scalalogging.log4j.Logging
import scala._
import junto.config.Label
import scala.collection.TraversableOnce

/**
 * Class for MAD algorithm, providing MAD specific implementation details
 * as extension of Adsorption.
 */
class ModifiedAdsorption (g: Graph, keepTopKLabels: Int, mu1: Double, mu2: Double, mu3: Double)
extends Adsorption (g, keepTopKLabels, mu1, mu2, mu3) {

  private var initialEstimatedScores : TraversableOnce[Label] = _
  val normalizationConstants = MadHelper.computeNormalizationConstants(g, mu1, mu2, mu3)

  def normalizeScores (vertex: Vertex, scores: TObjectDoubleHashMap[String]) {
    ProbUtil.DivScores(scores, normalizationConstants.get(vertex.name))
  }

  // multiplier for MAD update: (p_v_cont * w_vu + p_u_cont * w_uv) where u is neighbor
  def getMultiplier (vName: String, vertex: Vertex, neighName: String, neighbor: Vertex) =
    (vertex.pcontinue * vertex.GetNeighborWeight(neighName) +
     neighbor.pcontinue * neighbor.GetNeighborWeight(vName))

  def setInitialEstimatedScores(initialScores : TraversableOnce[Label]) : Adsorption = {
    initialEstimatedScores = initialScores

    this
  }

  //modified: added new method
  override def prepareGraph (graph: Graph) {
    AdsorptionHelper.prepareGraph(graph, initialEstimatedScores)
  }
}

// Helper methods for the MAD algorithm
object MadHelper {

  // Precomputes M_ii normalization (see algorithm in Talukdar and Crammer 2009)
  def computeNormalizationConstants (
    g: Graph, mu1: Double, mu2: Double, mu3: Double
  ): TObjectDoubleHashMap[String] = {

    val norms = new TObjectDoubleHashMap[String]
    g.vertices.keySet.foreach { 
      vName => {
        val vertex = g.vertices.get(vName)
        
        var totalNeighWeight = 0.0
        val nIter = vertex.neighbors.iterator
        while (nIter.hasNext) {
          nIter.advance
          totalNeighWeight += vertex.pcontinue * nIter.value
          val neigh = g.vertices.get(nIter.key)
          totalNeighWeight += neigh.pcontinue * neigh.GetNeighborWeight(vertex.name)
        }
		
        //mii = mu1 x p^{inj} + 0.5 * mu2 x \sum_j (p_{i}^{cont} W_{ij} + p_{j}^{cont} W_{ji}) + mu3
        val mii = mu1 * vertex.pinject + mu2 * totalNeighWeight + mu3
        norms.put(vName, mii)
      }
    }
    norms
  }

}



/**
 * Class for the original Baluja et al algorithm, providing specific implementation
 * details as extension of Adsorption.
 */
class OriginalAdsorption (g: Graph, keepTopKLabels: Int, mu1: Double, mu2: Double, mu3: Double)
extends Adsorption (g, keepTopKLabels, mu1, mu2, mu3) {

  def normalizeScores (vertex: Vertex, scores: TObjectDoubleHashMap[String]) {
    ProbUtil.Normalize(scores, keepTopKLabels)
  }

  // multiplier for Adsorption update: p_v_cont * w_uv (where u is neighbor)
  def getMultiplier (vName: String, vertex: Vertex, neighName: String, neighbor: Vertex) =
      vertex.pcontinue * neighbor.GetNeighborWeight(vName)

  override def normalizeIfNecessary (scores: TObjectDoubleHashMap[String]) { 
    ProbUtil.Normalize(scores) 
  }

}

object AdsorptionHelper {

  // -- normalize edge weights
  // -- remove dummy label from injected or estimate labels.
  // -- if seed node, then initialize estimated labels with injected
  def prepareGraph (graph: Graph) {
    for (vName <- graph.vertices.keySet) {
      val v: Vertex = graph.vertices.get(vName)
      
      // remove dummy label: after normalization, some of the distributions
      // may not be valid probability distributions, but that is fine as the
      // algorithm doesn't require the scores to be normalized (to start with)
      v.SetInjectedLabelScore(Constants.GetDummyLabel, 0.0)
      
      if (v.isSeedNode) {
        val injLabIter = v.injectedLabels.iterator
        while (injLabIter.hasNext) {
          injLabIter.advance
          v.SetInjectedLabelScore(injLabIter.key, injLabIter.value)
        }
        v.SetEstimatedLabelScores(new TObjectDoubleHashMap[String](v.injectedLabels))
      } else {
        // remove dummy label
        v.SetEstimatedLabelScore(Constants.GetDummyLabel, 0.0);				
      }
      //modify:store Type2Probability values in estimatedLabelScore
    }
  }

  def prepareGraph (graph: Graph, initialEstimatedScores: TraversableOnce[Label]) {
    for (vName <- graph.vertices.keySet) {
      val v: Vertex = graph.vertices.get(vName)

      // remove dummy label: after normalization, some of the distributions
      // may not be valid probability distributions, but that is fine as the
      // algorithm doesn't require the scores to be normalized (to start with)
      v.SetInjectedLabelScore(Constants.GetDummyLabel, 0.0)

      if (v.isSeedNode) {
        val injLabIter = v.injectedLabels.iterator
        while (injLabIter.hasNext) {
          injLabIter.advance
          v.SetInjectedLabelScore(injLabIter.key, injLabIter.value)
        }
        v.SetEstimatedLabelScores(new TObjectDoubleHashMap[String](v.injectedLabels))
      } else {
        // remove dummy label
        v.SetEstimatedLabelScore(Constants.GetDummyLabel, 0.0);
      }

    }

    //modified: store Type2Probability values in estimatedLabelScore
    if (initialEstimatedScores != null && initialEstimatedScores.nonEmpty){
      for (estimatedScore <- initialEstimatedScores){
        val vertex = graph.vertices.get(estimatedScore.vertex)
        if (vertex != null){
          vertex.SetEstimatedLabelScore(estimatedScore.label, estimatedScore.score)
        }
      }
    }
  }

}


/**
 * Parent class for Adsorption algorithms.
 */
abstract class Adsorption (g: Graph, keepTopKLabels: Int, mu1: Double, mu2: Double, mu3: Double)
extends LabelPropagationAlgorithm(g) 
with Logging {

  // Normalization is needed only for the original Adsorption
  // algorithm.  After normalization, we have the weighted
  // neighborhood label distribution for the current node.
  def normalizeIfNecessary (scores: TObjectDoubleHashMap[String]) { }

  def normalizeScores (vertex: Vertex, scores: TObjectDoubleHashMap[String]): Unit

  def getMultiplier (vName: String, vertex: Vertex, neighName: String, neighbor: Vertex): Double

  def prepareGraph(graph: Graph) {
    AdsorptionHelper.prepareGraph(g)
  }

  def run (maxIter: Int, useBipartiteOptimization: Boolean,
           verbose: Boolean, resultList: ArrayList[Map[String,Double]]) {
		
    //modified: AdsorptionHelper.prepareGraph(g)
    prepareGraph(g)
		
    logger.debug(
      "after_iteration " + 0 +
      " objective: " + getGraphObjective +
      " precision: " + GraphEval.GetAccuracy(g) +
      " rmse: " + GraphEval.GetRMSE(g) +
      " mrr_train: " + GraphEval.GetAverageTrainMRR(g) +
      " mrr_test: " + GraphEval.GetAverageTestMRR(g))
    
    val globalStartTime = System.currentTimeMillis
    for (iter <- 1 to maxIter) {
      logger.debug(s"Iteration: $iter")
      /*val message = "Iteration: " + iter
      logger debug message*/
			
      val startTime = System.currentTimeMillis

      val newDist =  new HashMap[String, TObjectDoubleHashMap[String]]

      for (vName <- g.vertices.keySet) {
			
        val v: Vertex = g.vertices.get(vName)

        val vertexNewDist = new TObjectDoubleHashMap[String]
							
        // compute weighted neighborhood label distribution
        for (neighName <- v.GetNeighborNames) {
          val neigh: Vertex = g.vertices.get(neighName)
          val mult = getMultiplier(vName, v, neighName, neigh)

          if (verbose)
            logger.info(v.name + " " + v.pcontinue + " " +
                    v.GetNeighborWeight(neighName) + " " +
                    neigh.pcontinue + " " + neigh.GetNeighborWeight(vName))

          if (mult <= 0) 
            throw new RuntimeException("Non-positive weighted edge:>>" +
                                       neigh.name + "-->" + v.name + "<<" + " " + mult)

          ProbUtil.AddScores(vertexNewDist, mult * mu2, neigh.estimatedLabels)
        }
				
        if (verbose)
          logger.debug("Before norm: " + v.name + " " + ProbUtil.GetSum(vertexNewDist))

        normalizeIfNecessary(vertexNewDist)
								
        if (verbose) 
          logger.debug("After norm: " + v.name + " " + ProbUtil.GetSum(vertexNewDist))
				
        // add injection probability
        ProbUtil.AddScores(vertexNewDist, v.pinject * mu1, v.injectedLabels)
	
        if (verbose)
          logger.debug(iter + " after_inj " + v.name + " " +
                  ProbUtil.GetSum(vertexNewDist) + 
                  " " + CollectionUtil.Map2String(vertexNewDist) +
                  " mu1: " + mu1)

        // add dummy label distribution
        ProbUtil.AddScores(vertexNewDist,
                           v.pabandon * mu3,
                           Constants.GetDummyLabelDist)
				
        if (verbose)
          logger.info(
                  iter + " after_dummy " + v.name + " " +
                  ProbUtil.GetSum(vertexNewDist) + " " +
                  CollectionUtil.Map2String(vertexNewDist) +
                  " injected: " + CollectionUtil.Map2String(v.injectedLabels))
				
        // keep only the top scoring k labels, this is particularly useful
        // when a large number of labels are involved.
        if (keepTopKLabels < Integer.MAX_VALUE) {
          ProbUtil.KeepTopScoringKeys(vertexNewDist, keepTopKLabels)
          if (vertexNewDist.size > keepTopKLabels)
            throw new RuntimeException("size mismatch: " + 
                                       vertexNewDist.size + " " + keepTopKLabels)
        }
				
        // normalize in case of Adsorption
        normalizeScores(v, vertexNewDist)

        // Store the new distribution for later update
        newDist.put(vName, vertexNewDist)
      }	

      var deltaLabelDiff = 0.0
      var totalColumnUpdates = 0
      var totalEntityUpdates = 0
		
      // update all vertices with new estimated label scores
      for (vName <- g.vertices.keySet) {
        val v: Vertex = g.vertices.get(vName)
        val vertexNewDist = newDist.get(vName)
				
        if (!useBipartiteOptimization) {
          deltaLabelDiff += 
            ProbUtil.GetDifferenceNorm2Squarred(v.estimatedLabels, 1.0, 
                                                vertexNewDist, 1.0)
          v.SetEstimatedLabelScores(vertexNewDist)
        } else {
          // update column node labels on odd iterations
          if (Flags.IsColumnNode(vName) && (iter % 2 == 0)) {
            totalColumnUpdates += 1
            deltaLabelDiff += 
              ProbUtil.GetDifferenceNorm2Squarred(v.estimatedLabels, 1.0,
                                                vertexNewDist, 1.0)
            v.SetEstimatedLabelScores(vertexNewDist)
          }
						
          // update entity labels on even iterations
          if (!Flags.IsColumnNode(vName) && (iter % 2 == 1)) {
            totalEntityUpdates += 1 
            deltaLabelDiff += 
              ProbUtil.GetDifferenceNorm2Squarred(v.estimatedLabels, 1.0,
                                                vertexNewDist, 1.0)
            v.SetEstimatedLabelScores(vertexNewDist)
          }
        }
      }
			
      val endTime = System.currentTimeMillis
			
      // clear map
      newDist.clear
			
      val totalNodes = g.vertices.size
      val deltaLabelDiffPerNode = (1.0 * deltaLabelDiff) / totalNodes

      val res = Map(Constants.GetMRRString -> GraphEval.GetAverageTestMRR(g), Constants.GetPrecisionString -> GraphEval.GetAccuracy(g))
      /*val res = Map(Constants.GetMRRString -> GraphEval.GetAverageTestMRR g)
      res.add(Constants.GetPrecisionString -> GraphEval.GetAccuracy g)*/

      resultList.add(res)


      logger.debug(
        "after_iteration " + iter +
        " objective: " + getGraphObjective +
        " accuracy: " + res(Constants.GetPrecisionString) +
        " rmse: " + GraphEval.GetRMSE(g) +
        " time: " + (endTime - startTime) +
        " label_diff_per_node: " + deltaLabelDiffPerNode +
        " mrr_train: " + GraphEval.GetAverageTrainMRR(g) +
        " mrr_test: " + res(Constants.GetMRRString) +
        " column_updates: " + totalColumnUpdates +
        " entity_updates: " + totalEntityUpdates + "\n")
      /*logger debug "after_iteration " + iter +
        " objective: " + getGraphObjective +
        " accuracy: " + res(Constants.GetPrecisionString) +
        " rmse: " + GraphEval.GetRMSE(g) +
        " time: " + (endTime - startTime) +
        " label_diff_per_node: " + deltaLabelDiffPerNode +
        " mrr_train: " + GraphEval.GetAverageTrainMRR(g) +
        " mrr_test: " + res(Constants.GetMRRString) +
        " column_updates: " + totalColumnUpdates +
        " entity_updates: " + totalEntityUpdates + "\n"*/
			
    }
    logger.debug("")

    val globalEndTime = System.currentTimeMillis
    logger.debug("TIME: " + (globalEndTime-globalStartTime)/1000.0)
		
  }
	
  def getObjective (v: Vertex): Double = {

    // difference with injected labels
    val seedObjective = 
      if (v.isSeedNode)
        (mu1 * v.pinject *
         ProbUtil.GetDifferenceNorm2Squarred(v.injectedLabels, 1, v.estimatedLabels, 1))
      else
        0.0
	
    // difference with labels of neighbors
    val neighObjective = v.GetNeighborNames.map(
      neighbor => (mu2 * v.GetNeighborWeight(neighbor) *
                   ProbUtil.GetDifferenceNorm2Squarred(v.estimatedLabels, 1,
                                                       g.vertices.get(neighbor).estimatedLabels, 1))
    ).sum

    // difference with dummy labels
    val dummyObjective = mu3 * ProbUtil.GetDifferenceNorm2Squarred(
      Constants.GetDummyLabelDist, v.pabandon, v.estimatedLabels, 1
    )
    
    seedObjective + neighObjective + dummyObjective
  }

}
