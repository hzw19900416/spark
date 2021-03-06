/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package org.apache.spark.ml.optim.aggregator

import org.apache.spark.broadcast.Broadcast
import org.apache.spark.internal.Logging
import org.apache.spark.ml.feature.InstanceBlock
import org.apache.spark.ml.linalg._

/**
 * HuberBlockAggregator computes the gradient and loss used in Huber Regression
 * for blocks in sparse or dense matrix in an online fashion.
 *
 * Two HuberBlockAggregator can be merged together to have a summary of loss and
 * gradient of the corresponding joint dataset.
 *
 * NOTE: The feature values are expected to already have be scaled (multiplied by bcInverseStd,
 * but NOT centered) before computation.
 *
 * @param bcCoefficients The coefficients corresponding to the features.
 * @param fitIntercept Whether to fit an intercept term. When true, will perform data centering
 *                     in a virtual way. Then we MUST adjust the intercept of both initial
 *                     coefficients and final solution in the caller.
 */
private[ml] class HuberBlockAggregator(
    bcInverseStd: Broadcast[Array[Double]],
    bcScaledMean: Broadcast[Array[Double]],
    fitIntercept: Boolean,
    epsilon: Double)(bcCoefficients: Broadcast[Vector])
  extends DifferentiableLossAggregator[InstanceBlock, HuberBlockAggregator]
  with Logging {

  if (fitIntercept) {
    require(bcScaledMean != null && bcScaledMean.value.length == bcInverseStd.value.length,
      "scaled means is required when center the vectors")
  }

  private val numFeatures = bcInverseStd.value.length
  protected override val dim: Int = bcCoefficients.value.size

  @transient private lazy val coefficientsArray = bcCoefficients.value match {
    case DenseVector(values) => values
    case _ => throw new IllegalArgumentException(s"coefficients only supports dense vector but " +
      s"got type ${bcCoefficients.value.getClass}.)")
  }

  @transient private lazy val linear = new DenseVector(coefficientsArray.take(numFeatures))

  // pre-computed margin of an empty vector.
  // with this variable as an offset, for a sparse vector, we only need to
  // deal with non-zero values in prediction.
  private lazy val marginOffset = if (fitIntercept) {
    coefficientsArray(dim - 2) -
      BLAS.javaBLAS.ddot(numFeatures, coefficientsArray, 1, bcScaledMean.value, 1)
  } else {
    Double.NaN
  }

  /**
   * Add a new training instance block to this HuberBlockAggregator, and update the loss
   * and gradient of the objective function.
   *
   * @param block The instance block of data point to be added.
   * @return This HuberBlockAggregator object.
   */
  def add(block: InstanceBlock): this.type = {
    require(block.matrix.isTransposed)
    require(numFeatures == block.numFeatures, s"Dimensions mismatch when adding new " +
      s"instance. Expecting $numFeatures but got ${block.numFeatures}.")
    require(block.weightIter.forall(_ >= 0),
      s"instance weights ${block.weightIter.mkString("[", ",", "]")} has to be >= 0.0")

    if (block.weightIter.forall(_ == 0)) return this
    val size = block.size

    // vec/arr here represents margins
    val vec = new DenseVector(Array.ofDim[Double](size))
    val arr = vec.values
    if (fitIntercept) java.util.Arrays.fill(arr, marginOffset)
    BLAS.gemv(1.0, block.matrix, linear, 1.0, vec)

    // in-place convert margins to multiplier
    // then, vec/arr represents multiplier
    val sigma = coefficientsArray.last
    var sigmaGradSum = 0.0
    var localLossSum = 0.0
    var localWeightSum = 0.0
    var multiplierSum = 0.0
    var i = 0
    while (i < size) {
      val weight = block.getWeight(i)
      localWeightSum += weight
      if (weight > 0) {
        val label = block.getLabel(i)
        val margin = arr(i)
        val linearLoss = label - margin

        if (math.abs(linearLoss) <= sigma * epsilon) {
          localLossSum += 0.5 * weight * (sigma + math.pow(linearLoss, 2.0) / sigma)
          val linearLossDivSigma = linearLoss / sigma
          val multiplier = -1.0 * weight * linearLossDivSigma
          arr(i) = multiplier
          multiplierSum += multiplier
          sigmaGradSum += 0.5 * weight * (1.0 - math.pow(linearLossDivSigma, 2.0))
        } else {
          localLossSum += 0.5 * weight *
            (sigma + 2.0 * epsilon * math.abs(linearLoss) - sigma * epsilon * epsilon)
          val sign = if (linearLoss >= 0) -1.0 else 1.0
          val multiplier = weight * sign * epsilon
          arr(i) = multiplier
          multiplierSum += multiplier
          sigmaGradSum += 0.5 * weight * (1.0 - epsilon * epsilon)
        }
      } else { arr(i) = 0.0 }
      i += 1
    }
    lossSum += localLossSum
    weightSum += localWeightSum

    block.matrix match {
      case dm: DenseMatrix =>
        BLAS.nativeBLAS.dgemv("N", dm.numCols, dm.numRows, 1.0, dm.values, dm.numCols,
          arr, 1, 1.0, gradientSumArray, 1)

      case sm: SparseMatrix =>
        val linearGradSumVec = Vectors.zeros(numFeatures).toDense
        BLAS.gemv(1.0, sm.transpose, vec, 0.0, linearGradSumVec)
        BLAS.javaBLAS.daxpy(numFeatures, 1.0, linearGradSumVec.values, 1,
          gradientSumArray, 1)
    }

    if (fitIntercept) {
      // above update of the linear part of gradientSumArray does NOT take the centering
      // into account, here we need to adjust this part.
      BLAS.javaBLAS.daxpy(numFeatures, -multiplierSum, bcScaledMean.value, 1,
        gradientSumArray, 1)

      // update the intercept part of gradientSumArray
      gradientSumArray(dim - 2) += multiplierSum
    }

    gradientSumArray(dim - 1) += sigmaGradSum

    this
  }
}
