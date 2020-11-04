// Copyright (C) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License. See LICENSE in project root for information.

package com.microsoft.ml.spark.lightgbm

import java.io._
import java.net._

import com.microsoft.ml.lightgbm._
import com.microsoft.ml.spark.core.env.StreamUtilities._
import org.apache.spark.{BarrierTaskContext, TaskContext}
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.ml.attribute._
import org.apache.spark.ml.linalg.{DenseVector, SparseVector}
import org.apache.spark.ml.linalg.SQLDataTypes.VectorType
import org.apache.spark.sql.Row
import org.apache.spark.sql.types.StructType
import org.slf4j.Logger

case class NetworkParams(defaultListenPort: Int, addr: String, port: Int, barrierExecutionMode: Boolean)
case class ColumnParams(labelColumn: String, featuresColumn: String, weightColumn: Option[String],
                        initScoreColumn: Option[String], groupColumn: Option[String])

private object TrainUtils extends Serializable {

  def generateDataset(rows: Array[Row], columnParams: ColumnParams,
                      referenceDataset: Option[LightGBMDataset], schema: StructType,
                      log: Logger, trainParams: TrainParams): Option[LightGBMDataset] = {
    val numRows = rows.length
    val labels = rows.map(row => row.getDouble(schema.fieldIndex(columnParams.labelColumn)))
    val hrow = rows.head
    var datasetPtr: Option[LightGBMDataset] = None
    datasetPtr =
      if (hrow.get(schema.fieldIndex(columnParams.featuresColumn)).isInstanceOf[DenseVector]) {
        val rowsAsDoubleArray = rows.map(row => row.get(schema.fieldIndex(columnParams.featuresColumn)) match {
          case dense: DenseVector => dense.toArray
          case sparse: SparseVector => sparse.toDense.toArray
        })
        val numCols = rowsAsDoubleArray.head.length
        val slotNames = getSlotNames(schema, columnParams.featuresColumn, numCols, trainParams)
        log.info(s"LightGBM worker generating dense dataset with $numRows rows and $numCols columns")
        Some(LightGBMUtils.generateDenseDataset(numRows, rowsAsDoubleArray, referenceDataset,
          slotNames, trainParams))
      } else {
        val rowsAsSparse = rows.map(row => row.get(schema.fieldIndex(columnParams.featuresColumn)) match {
          case dense: DenseVector => dense.toSparse
          case sparse: SparseVector => sparse
        })
        val numCols = rowsAsSparse(0).size
        val slotNames = getSlotNames(schema, columnParams.featuresColumn, numCols, trainParams)
        log.info(s"LightGBM worker generating sparse dataset with $numRows rows and $numCols columns")
        Some(LightGBMUtils.generateSparseDataset(rowsAsSparse, referenceDataset, slotNames, trainParams))
      }

    // Validate generated dataset has the correct number of rows and cols
    datasetPtr.get.validateDataset()
    datasetPtr.get.addFloatField(labels, "label", numRows)
    columnParams.weightColumn.foreach { col =>
      val weights = rows.map(row => row.getDouble(schema.fieldIndex(col)))
      datasetPtr.get.addFloatField(weights, "weight", numRows)
    }
    addInitScoreColumn(rows, columnParams.initScoreColumn, datasetPtr, numRows, schema)
    addGroupColumn(rows, columnParams.groupColumn, datasetPtr, numRows, schema)

    datasetPtr
  }

  trait CardinalityType[T]

  object CardinalityTypes {

    implicit object LongType extends CardinalityType[Long]

    implicit object IntType extends CardinalityType[Int]

    implicit object StringType extends CardinalityType[String]

  }

  import CardinalityTypes._

  def addInitScoreColumn(rows: Array[Row], initScoreColumn: Option[String],
                         datasetPtr: Option[LightGBMDataset], numRows: Int, schema: StructType): Unit = {
    initScoreColumn.foreach { col =>
      val field = schema.fields(schema.fieldIndex(col))
      if (field.dataType == VectorType) {
        val initScores = rows.map(row => row.get(schema.fieldIndex(col)).asInstanceOf[DenseVector])
        // Calculate # rows * # classes in multiclass case
        val initScoresLength = initScores.length
        val totalLength = initScoresLength * initScores(0).size
        val flattenedInitScores = new Array[Double](totalLength)
        initScores.zipWithIndex.foreach { case (rowVector, rowIndex) =>
          rowVector.values.zipWithIndex.foreach { case (rowValue, colIndex) =>
            flattenedInitScores(colIndex * initScoresLength + rowIndex) = rowValue
          }
        }
        datasetPtr.get.addDoubleField(flattenedInitScores, "init_score", numRows)
      } else {
        val initScores = rows.map(row => row.getDouble(schema.fieldIndex(col)))
        datasetPtr.get.addDoubleField(initScores, "init_score", numRows)
      }
    }
  }

  def addGroupColumn(rows: Array[Row], groupColumn: Option[String],
                     datasetPtr: Option[LightGBMDataset], numRows: Int, schema: StructType): Unit = {
    groupColumn.foreach { col =>
      val datatype = schema.fields(schema.fieldIndex(col)).dataType

      if (datatype != org.apache.spark.sql.types.IntegerType
        && datatype != org.apache.spark.sql.types.LongType
        && datatype != org.apache.spark.sql.types.StringType) {
        throw new IllegalArgumentException(
          s"group column $col must be of type Long, Int or String but is ${datatype.typeName}")
      }

      val colIdx = schema.fieldIndex(col)

      // Convert to distinct count (note ranker should have sorted within partition by group id)
      // We use a triplet of a list of cardinalities, last unqiue value and unique value count
      val groupCardinality = datatype match {
        case org.apache.spark.sql.types.IntegerType => countCardinality(rows.map(row => row.getInt(colIdx)))
        case org.apache.spark.sql.types.LongType => countCardinality(rows.map(row => row.getLong(colIdx)))
        case org.apache.spark.sql.types.StringType => countCardinality(rows.map(row => row.getString(colIdx)))
      }

      datasetPtr.get.addIntField(groupCardinality, "group", groupCardinality.length)
    }
  }

  case class CardinalityTriplet[T](groupCounts: List[Int], currentValue: T, currentCount: Int)

  def countCardinality[T](input: Seq[T])(implicit ev: CardinalityType[T]): Array[Int] = {
    val default: T = null.asInstanceOf[T]

    val cardinalityTriplet = input.foldLeft(CardinalityTriplet(List.empty[Int], default, 0)) {
      case (listValue: CardinalityTriplet[T], currentValue) =>

        if (listValue.groupCounts.isEmpty && listValue.currentCount == 0) {
          // Base case, keep list as empty and set cardinality to 1
          CardinalityTriplet(listValue.groupCounts, currentValue, 1)
        }
        else if (listValue.currentValue == currentValue) {
          // Encountered same value
          CardinalityTriplet(listValue.groupCounts, currentValue, listValue.currentCount + 1)
        }
        else {
          // New value, need to reset counter and add new cardinality to list
          CardinalityTriplet(listValue.currentCount :: listValue.groupCounts, currentValue, 1)
        }
    }

    val groupCardinality = (cardinalityTriplet.currentCount :: cardinalityTriplet.groupCounts).reverse.toArray
    groupCardinality
  }

  def createBooster(trainParams: TrainParams, trainDatasetPtr: Option[LightGBMDataset],
                    validDatasetPtr: Option[LightGBMDataset]): Option[SWIGTYPE_p_void] = {
    // Create the booster
    val boosterOutPtr = lightgbmlib.voidpp_handle()
    val parameters = trainParams.toString()
    LightGBMUtils.validate(lightgbmlib.LGBM_BoosterCreate(trainDatasetPtr.map(_.dataset).get,
      parameters, boosterOutPtr), "Booster")
    val boosterPtr = Some(lightgbmlib.voidpp_value(boosterOutPtr))
    trainParams.modelString.foreach { modelStr =>
      val booster = LightGBMUtils.getBoosterPtrFromModelString(modelStr)
      LightGBMUtils.validate(lightgbmlib.LGBM_BoosterMerge(boosterPtr.get, booster), "Booster Merge")
    }
    validDatasetPtr.foreach { lgbmdataset =>
      LightGBMUtils.validate(lightgbmlib.LGBM_BoosterAddValidData(boosterPtr.get,
        lgbmdataset.dataset), "Add Validation Dataset")
    }
    boosterPtr
  }

  def saveBoosterToString(boosterPtr: Option[SWIGTYPE_p_void], log: Logger): String = {
    val bufferLength = LightGBMConstants.DefaultBufferLength
    val bufferOutLengthPtr = lightgbmlib.new_int64_tp()
    lightgbmlib.LGBM_BoosterSaveModelToStringSWIG(boosterPtr.get, 0, -1, bufferLength, bufferOutLengthPtr)
  }

  def getEvalNames(boosterPtr: Option[SWIGTYPE_p_void]): Array[String] = {
    // Need to keep track of best scores for each metric, see callback.py in lightgbm for reference
    // For debugging, can get metric names
    val stringArrayHandle = lightgbmlib.LGBM_BoosterGetEvalNamesSWIG(boosterPtr.get)
    LightGBMUtils.validateArray(stringArrayHandle, "Booster Get Eval Names")
    val evalNames = lightgbmlib.StringArrayHandle_get_strings(stringArrayHandle)
    lightgbmlib.StringArrayHandle_free(stringArrayHandle)
    evalNames
  }

  def beforeTrainIteration(batchIndex: Int, partitionId: Int, curIters: Int, log: Logger,
                           trainParams: TrainParams, boosterPtr: Option[SWIGTYPE_p_void], hasValid: Boolean): Unit = {
    if (trainParams.delegate.isDefined) {
      trainParams.delegate.get.beforeTrainIteration(batchIndex, partitionId, curIters, log, trainParams, boosterPtr,
        hasValid)
    }
  }

  def afterTrainIteration(batchIndex: Int, partitionId: Int, curIters: Int, log: Logger,
                          trainParams: TrainParams, boosterPtr: Option[SWIGTYPE_p_void], hasValid: Boolean,
                          isFinished: Boolean,
                          trainEvalResults: Option[Map[String, Double]],
                          validEvalResults: Option[Map[String, Double]]): Unit = {
    if (trainParams.delegate.isDefined) {
      trainParams.delegate.get.afterTrainIteration(batchIndex, partitionId, curIters, log, trainParams, boosterPtr,
        hasValid, isFinished, trainEvalResults, validEvalResults)
    }
  }

  def getLearningRate(batchIndex: Int, partitionId: Int, curIters: Int, log: Logger, trainParams: TrainParams,
                      previousLearningRate: Double): Double = {
    trainParams.delegate match {
      case Some(delegate) => delegate.getLearningRate(batchIndex, partitionId, curIters, log, trainParams,
          previousLearningRate)
      case None => previousLearningRate
    }
  }

  def trainCore(batchIndex: Int, trainParams: TrainParams, boosterPtr: Option[SWIGTYPE_p_void],
                log: Logger, hasValid: Boolean): Unit = {
    val isFinishedPtr = lightgbmlib.new_intp()
    var isFinished = false
    var iters = 0
    val evalNames = getEvalNames(boosterPtr)
    val evalCounts = evalNames.length
    val bestScore = new Array[Double](evalCounts)
    val bestScores = new Array[Array[Double]](evalCounts)
    val bestIter = new Array[Int](evalCounts)
    val partitionId = TaskContext.getPartitionId
    var learningRate: Double = trainParams.learningRate
    while (!isFinished && iters < trainParams.numIterations) {
      beforeTrainIteration(batchIndex, partitionId, iters, log, trainParams, boosterPtr, hasValid)
      val newLearningRate = getLearningRate(batchIndex, partitionId, iters, log, trainParams,
        learningRate)
      if (newLearningRate != learningRate) {
        log.info(s"LightGBM worker calling LGBM_BoosterResetParameter to reset learningRate" +
          s" (newLearningRate: $newLearningRate)")
        LightGBMUtils.validate(lightgbmlib.LGBM_BoosterResetParameter(boosterPtr.get,
          s"learning_rate=$newLearningRate"), "Booster Reset learning_rate Param")
        learningRate = newLearningRate
      }

      try {
        log.info("LightGBM worker calling LGBM_BoosterUpdateOneIter")
        val result = lightgbmlib.LGBM_BoosterUpdateOneIter(boosterPtr.get, isFinishedPtr)
        LightGBMUtils.validate(result, "Booster Update One Iter")
        isFinished = lightgbmlib.intp_value(isFinishedPtr) == 1
        log.info("LightGBM running iteration: " + iters + " with result: " +
          result + " and is finished: " + isFinished)
      } catch {
        case _: java.lang.Exception =>
          isFinished = true
          log.warn("LightGBM reached early termination on one worker," +
            " stopping training on worker. This message should rarely occur")
      }

      val trainEvalResults: Option[Map[String, Double]] = if (trainParams.isProvideTrainingMetric && !isFinished) {
        val trainResults = lightgbmlib.new_doubleArray(evalNames.length.toLong)
        val dummyEvalCountsPtr = lightgbmlib.new_intp()
        val resultEval = lightgbmlib.LGBM_BoosterGetEval(boosterPtr.get, 0, dummyEvalCountsPtr, trainResults)
        lightgbmlib.delete_intp(dummyEvalCountsPtr)
        LightGBMUtils.validate(resultEval, "Booster Get Train Eval")

        val results: Array[(String, Double)] = evalNames.zipWithIndex.map { case (evalName, index) =>
          val score = lightgbmlib.doubleArray_getitem(trainResults, index.toLong)
          log.info(s"Train $evalName=$score")
          (evalName, score)
        }

        Option(Map(results:_*))
      } else {
        None
      }

      val validEvalResults: Option[Map[String, Double]] = if (hasValid && !isFinished) {
        val evalResults = lightgbmlib.new_doubleArray(evalNames.length.toLong)
        val dummyEvalCountsPtr = lightgbmlib.new_intp()
        val resultEval = lightgbmlib.LGBM_BoosterGetEval(boosterPtr.get, 1, dummyEvalCountsPtr, evalResults)
        lightgbmlib.delete_intp(dummyEvalCountsPtr)
        LightGBMUtils.validate(resultEval, "Booster Get Valid Eval")
        val results: Array[(String, Double)] = evalNames.zipWithIndex.map { case (evalName, index) =>
          val score = lightgbmlib.doubleArray_getitem(evalResults, index.toLong)
          log.info(s"Valid $evalName=$score")
          val cmp =
            if (evalName.startsWith("auc") || evalName.startsWith("ndcg@") || evalName.startsWith("map@"))
              (x: Double, y: Double, tol: Double) => x - y > tol
            else
              (x: Double, y: Double, tol: Double) => x - y < tol
          if (bestScores(index) == null || cmp(score, bestScore(index), trainParams.improvementTolerance)) {
            bestScore(index) = score
            bestIter(index) = iters
            bestScores(index) = evalNames.indices
              .map(j => lightgbmlib.doubleArray_getitem(evalResults, j.toLong)).toArray
          } else if (iters - bestIter(index) >= trainParams.earlyStoppingRound) {
            isFinished = true
            log.info("Early stopping, best iteration is " + bestIter(index))
          }

          (evalName, score)
        }

        lightgbmlib.delete_doubleArray(evalResults)

        Option(Map(results:_*))
      } else {
        None
      }

      afterTrainIteration(batchIndex, partitionId, iters, log, trainParams, boosterPtr, hasValid, isFinished,
        trainEvalResults, validEvalResults)

      iters = iters + 1
    }
  }

  def getSlotNames(schema: StructType, featuresColumn: String, numCols: Int,
                   trainParams: TrainParams): Option[Array[String]] = {
    if (trainParams.featureNames.nonEmpty) {
      Some(trainParams.featureNames)
    } else {
      val featuresSchema = schema.fields(schema.fieldIndex(featuresColumn))
      val metadata = AttributeGroup.fromStructField(featuresSchema)
      if (metadata.attributes.isEmpty) None
      else if (metadata.attributes.get.isEmpty) None
      else {
        val colnames = (0 until numCols).map(_.toString).toArray
        metadata.attributes.get.foreach {
          case attr =>
            attr.index.foreach(index => colnames(index) = attr.name.getOrElse(index.toString))
        }
        Some(colnames)
      }
    }
  }

  def beforeGenerateTrainDataset(batchIndex: Int, columnParams: ColumnParams, schema: StructType,
                                 log: Logger, trainParams: TrainParams): Unit = {
    if(trainParams.delegate.isDefined) {
      trainParams.delegate.get.beforeGenerateTrainDataset(batchIndex, TaskContext.getPartitionId, columnParams,
        schema, log, trainParams)
    }
  }

  def afterGenerateTrainDataset(batchIndex: Int, columnParams: ColumnParams, schema: StructType,
                                 log: Logger, trainParams: TrainParams): Unit = {
    if(trainParams.delegate.isDefined) {
      trainParams.delegate.get.afterGenerateTrainDataset(batchIndex, TaskContext.getPartitionId, columnParams,
        schema, log, trainParams)
    }
  }

  def beforeGenerateValidDataset(batchIndex: Int, columnParams: ColumnParams, schema: StructType,
                                 log: Logger, trainParams: TrainParams): Unit = {
    if(trainParams.delegate.isDefined) {
      trainParams.delegate.get.beforeGenerateValidDataset(batchIndex, TaskContext.getPartitionId, columnParams,
        schema, log, trainParams)
    }
  }

  def afterGenerateValidDataset(batchIndex: Int, columnParams: ColumnParams, schema: StructType,
                                log: Logger, trainParams: TrainParams): Unit = {
    if(trainParams.delegate.isDefined) {
      trainParams.delegate.get.afterGenerateValidDataset(batchIndex, TaskContext.getPartitionId, columnParams,
        schema, log, trainParams)
    }
  }

  def translate(batchIndex: Int, columnParams: ColumnParams, validationData: Option[Broadcast[Array[Row]]],
                log: Logger, trainParams: TrainParams, schema: StructType,
                inputRows: Iterator[Row]): Iterator[LightGBMBooster] = {
    val rows = inputRows.toArray
    var trainDatasetPtr: Option[LightGBMDataset] = None
    var validDatasetPtr: Option[LightGBMDataset] = None
    try {
      beforeGenerateTrainDataset(batchIndex, columnParams, schema, log, trainParams)
      trainDatasetPtr = generateDataset(rows, columnParams, None, schema, log, trainParams)
      afterGenerateTrainDataset(batchIndex, columnParams, schema, log, trainParams)

      if (validationData.isDefined) {
        beforeGenerateValidDataset(batchIndex, columnParams, schema, log, trainParams)
        validDatasetPtr = generateDataset(validationData.get.value, columnParams, trainDatasetPtr,
          schema, log, trainParams)
        afterGenerateValidDataset(batchIndex, columnParams, schema, log, trainParams)
      }

      var boosterPtr: Option[SWIGTYPE_p_void] = None
      try {
        boosterPtr = createBooster(trainParams, trainDatasetPtr, validDatasetPtr)
        trainCore(batchIndex, trainParams, boosterPtr, log, validDatasetPtr.isDefined)
        val model = saveBoosterToString(boosterPtr, log)
        List[LightGBMBooster](new LightGBMBooster(model)).toIterator
      } finally {
        // Free booster
        boosterPtr.foreach { booster =>
          LightGBMUtils.validate(lightgbmlib.LGBM_BoosterFree(booster), "Finalize Booster")
        }
      }
    } finally {
      // Free datasets
      trainDatasetPtr.foreach(_.close())
      validDatasetPtr.foreach(_.close())
    }
  }

  private def findOpenPort(defaultListenPort: Int, numCoresPerExec: Int, log: Logger): Socket = {
    val basePort = defaultListenPort + (LightGBMUtils.getId() * numCoresPerExec)
    if (basePort > LightGBMConstants.MaxPort) {
      throw new Exception(s"Error: port $basePort out of range, possibly due to too many executors or unknown error")
    }
    var localListenPort = basePort
    var foundPort = false
    var workerServerSocket: Socket = null
    while (!foundPort) {
      try {
        workerServerSocket = new Socket()
        workerServerSocket.bind(new InetSocketAddress(localListenPort))
        foundPort = true
      } catch {
        case _: IOException =>
          log.warn(s"Could not bind to port $localListenPort...")
          localListenPort += 1
          if (localListenPort > LightGBMConstants.MaxPort) {
            throw new Exception(s"Error: port $basePort out of range, possibly due to networking or firewall issues")
          }
          if (localListenPort - basePort > 1000) {
            throw new Exception("Error: Could not find open port after 1k tries")
          }
      }
    }
    log.info(s"Successfully bound to port $localListenPort")
    workerServerSocket
  }

  def setFinishedStatus(networkParams: NetworkParams,
                        localListenPort: Int, log: Logger): Unit = {
    using(new Socket(networkParams.addr, networkParams.port)) {
      driverSocket =>
        using(new BufferedWriter(new OutputStreamWriter(driverSocket.getOutputStream))) {
          driverOutput =>
            log.info("sending finished status to driver")
            // If barrier execution mode enabled, create a barrier across tasks
            driverOutput.write(s"${LightGBMConstants.FinishedStatus}\n")
            driverOutput.flush()
        }.get
    }.get
  }

  def getNetworkInitNodes(networkParams: NetworkParams,
                          localListenPort: Int, log: Logger,
                          emptyPartition: Boolean): String = {
    using(new Socket(networkParams.addr, networkParams.port)) {
      driverSocket =>
        usingMany(Seq(new BufferedReader(new InputStreamReader(driverSocket.getInputStream)),
          new BufferedWriter(new OutputStreamWriter(driverSocket.getOutputStream)))) {
          io =>
            val driverInput = io(0).asInstanceOf[BufferedReader]
            val driverOutput = io(1).asInstanceOf[BufferedWriter]
            val workerStatus =
              if (emptyPartition) {
                log.info("send empty status to driver")
                LightGBMConstants.IgnoreStatus
              } else {
                val workerHost = driverSocket.getLocalAddress.getHostAddress
                val workerInfo = s"$workerHost:$localListenPort"
                log.info(s"send current worker info to driver: $workerInfo ")
                workerInfo
              }
            // Send the current host:port to the driver
            driverOutput.write(s"$workerStatus\n")
            driverOutput.flush()
            // If barrier execution mode enabled, create a barrier across tasks
            if (networkParams.barrierExecutionMode) {
              val context = BarrierTaskContext.get()
              context.barrier()
              if (context.partitionId() == 0) {
                setFinishedStatus(networkParams, localListenPort, log)
              }
            }
            if (workerStatus != LightGBMConstants.IgnoreStatus) {
              // Wait to get the list of nodes from the driver
              val nodes = driverInput.readLine()
              log.info(s"LightGBM worker got nodes for network init: $nodes")
              nodes
            } else {
              workerStatus
            }
        }.get
    }.get
  }

  def networkInit(nodes: String, localListenPort: Int, log: Logger, retry: Int, delay: Long): Unit = {
    try {
      LightGBMUtils.validate(lightgbmlib.LGBM_NetworkInit(nodes, localListenPort,
        LightGBMConstants.DefaultListenTimeout, nodes.split(",").length), "Network init")
    } catch {
      case ex@(_: Exception | _: Throwable) =>
        log.info(s"NetworkInit failed with exception on local port $localListenPort with exception: $ex")
        Thread.sleep(delay)
        if (retry > 0) {
          log.info(s"Retrying NetworkInit with local port $localListenPort")
          networkInit(nodes, localListenPort, log, retry - 1, delay * 2)
        } else {
          log.info(s"NetworkInit reached maximum exceptions on retry: $ex")
          throw ex
        }
    }
  }

  def trainLightGBM(batchIndex: Int, networkParams: NetworkParams, columnParams: ColumnParams,
                    validationData: Option[Broadcast[Array[Row]]], log: Logger,
                    trainParams: TrainParams, numCoresPerExec: Int, schema: StructType)
                   (inputRows: Iterator[Row]): Iterator[LightGBMBooster] = {
    log.info(s"starting training with parameters: ${trainParams.toString()}")
    val emptyPartition = !inputRows.hasNext
    // Ideally we would start the socket connections in the C layer, this opens us up for
    // race conditions in case other applications open sockets on cluster, but usually this
    // should not be a problem
    val (nodes, localListenPort) = using(findOpenPort(networkParams.defaultListenPort, numCoresPerExec, log)) {
      openPort =>
        val localListenPort = openPort.getLocalPort
        // Initialize the native library
        LightGBMUtils.initializeNativeLibrary()
        log.info(s"LightGBM worker connecting to host: ${networkParams.addr} and port: ${networkParams.port}")
        (getNetworkInitNodes(networkParams, localListenPort, log, emptyPartition), localListenPort)
    }.get

    if (emptyPartition) {
      log.warn("LightGBM worker encountered empty partition, for best performance ensure no partitions empty")
      List[LightGBMBooster]().toIterator
    } else {
      // Initialize the network communication
      log.info(s"LightGBM worker listening on: $localListenPort")
      try {
        val retries = 3
        val initialDelay = 1000L
        networkInit(nodes, localListenPort, log, retries, initialDelay)
        translate(batchIndex, columnParams, validationData, log, trainParams, schema, inputRows)
      } finally {
        // Finalize network when done
        LightGBMUtils.validate(lightgbmlib.LGBM_NetworkFree(), "Finalize network")
      }
    }
  }

}
