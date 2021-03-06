/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.dia.algorithms.mcc

import java.io.{File, PrintWriter}
import java.text.SimpleDateFormat

import org.dia.Parsers
import org.dia.core.{SciSparkContext, sciTensor}
import org.slf4j.Logger

import scala.collection.mutable
import scala.io.Source
import scala.language.implicitConversions

object MainNetcdfDFSMCC {
  val LOG: Logger = org.slf4j.LoggerFactory.getLogger(this.getClass)

  def main(args: Array[String]): Unit = {
    /**
     * Input arguements to the program :
     * args(1) - the spark master url. Example : spark://HOST_NAME:7077
     * args(2) - the number of desired partitions. Default : 2
     * args(3) - square matrix dimension. Default : 20
     * args(4) - variable name
     *
     */
    val masterURL = if (args.length <= 1) "local[2]" else args(1)
    val partCount = if (args.length <= 2) 2 else args(2).toInt
    val dimension = if (args.length <= 3) (20, 20) else (args(3).toInt, args(3).toInt)
    val variable = if (args.length <= 4) "ch4" else args(4)
    val hdfspath = if (args.length <= 5) "resources/MERG" else args(5)


    /**
     * Initialize the spark context to point to the master URL
     *
     */
    val sc = new SciSparkContext(masterURL, "DGTG : Distributed MCC Search")

    /**
     * Ingest the input file and construct the sRDD.
     * For MCC the sources are used to map date-indexes.
     * The metadata variable "FRAME" corresponds to an index.
     * The indices themselves are numbered with respect to
     * date-sorted order.
     *
     * Note if no hdfs path is given, then randomly generated matrices are used
     *
     */
    val sRDD = sc.NetcdfDFSFile(hdfspath, List("ch4"), partCount)

    val labeled = sRDD.map(p => {
      val source = p.metaData("SOURCE").split("/").last.split("_")(1)
      val FrameID = source.toInt
      p.insertDictionary(("FRAME", FrameID.toString))
      p
    })

    /**
     * The MCC algorithim : Mining for graph vertices and edges
     *
     * For each array N* where a N is the frame number and N* is the array
     * output the following pairs (N*, N), (N*, N + 1).
     *
     * After flatmapping the pairs and applying additional pre-processing
     * we have pairs of (X, Y) where X is a matrix and Y is a label.
     *
     * After grouping by Y and reordering pairs we pairs of the following
     * (N*, (N+1)*) which achieves the consecutive pairwise grouping
     * of frames.
     */
    //val reducedRes = labeled.map(p => p.reduceRectangleResolution(25, 8, 330))
    val filtered = labeled.map(p => p(variable) <= 241.0)
    val complete = filtered.flatMap(p => {
      List((p.metaData("FRAME").toInt, p), (p.metaData("FRAME").toInt + 1, p))
    }).groupBy(_._1)
      .map(p => p._2.map(e => e._2).toList)
      .filter(p => p.size > 1)
      .map(p => p.sortBy(_.metaData("FRAME").toInt))
      .map(p => (p(0), p(1)))



    /**
     * Core MCC
     * For each consecutive frame pair, find it's components.
     * For each component pairing, find if the elementwise
     * component pairing results in a zero matrix.
     * If not output a new edge pairing in the form ((Frame, Component), (Frame, Component))
     */
    val componentFrameRDD = complete.flatMap(p => {
      /**
       * First label the connected components in each pair.
       * The following example illustrates labelling.
       * [0,1,2,0]       [0,1,1,0]
       * [1,2,0,0]   ->  [1,1,0,0]
       * [0,0,0,1]       [0,0,0,2]
       *
       * Note that a tuple of (Matrix, MaxLabel) is returned
       * to denote the labelled elements and the highest label.
       * This way only one traverse is necessary instead of a 2nd traverse
       * to find the highest labelled.
       */
      val components1 = mccOps.labelConnectedComponents(p._1.tensor)
      val components2 = mccOps.labelConnectedComponents(p._2.tensor)

      /**
       * The labelled components are elementwise multiplied
       * to find overlapping regions. Non-overlapping regions
       * result in a 0.
       *
       * [0,1,1,0]       [0,1,1,0]     [0,1,1,0]
       * [1,1,0,0]   X   [2,0,0,0]  =  [2,0,0,0]
       * [0,0,0,2]       [0,0,0,3]     [0,0,0,6]
       *
       */
      val product = components1._1 * components2._1

      /**
       * The OverlappedPairsList keeps track of all points that
       * overlap between the labelled arrays. Note that the OverlappedPairsList
       * will have several duplicate pairs if their are large regions of overlap.
       *
       * This is achieved by iterating through the product array and noting
       * all points that are not 0.
       */
      var OverlappedPairsList = mutable.MutableList[(Double, Double)]()

      /**
       * The AreaMinMaxTable keeps track of the Area, Minimum value, and Maximum value
       * of all labelled regions in both components. For this reason the hash key has the following form :
       * 'F : C' where F = Frame Number and C = Component Number.
       * The AreaMinMaxTable is updated by the updateComponent function, which is called in the for loop.
       * TODO :: Extend it to have a lat lon bounds for component
       */
      var AreaMinMaxTable = new mutable.HashMap[String, (Double, Double, Double)]

      def updateComponent(label: Double, frame: String, value: Double): Unit = {
        if (label != 0.0) {
          var area = 1.0
          var max = Double.MinValue
          var min = Double.MaxValue
          val currentProperties = AreaMinMaxTable.get(frame + ":" + label)
          if (currentProperties != null && currentProperties.isDefined) {
            area = currentProperties.get._1
            max = currentProperties.get._2
            min = currentProperties.get._3
            if (value < min) min = value
            if (value > max) max = value
          } else {
            min = value
            max = value
          }
          area += 1
          AreaMinMaxTable += ((frame + ":" + label, (area, max, min)))
        }
      }

      for (row <- 0 to product.rows - 1) {
        for (col <- 0 to product.cols - 1) {
          // Find non-zero points in product array
          if (product(row, col) != 0.0) {
            // save components ids
            val value1 = components1._1(row, col)
            val value2 = components2._1(row, col)
            OverlappedPairsList += ((value1, value2))
          }
          updateComponent(components1._1(row, col), p._1.metaData("FRAME"), p._1.tensor(row, col))
          updateComponent(components2._1(row, col), p._2.metaData("FRAME"), p._2.tensor(row, col))
        }
      }

      /**
       * Once the overlapped pairs have been computed eliminate all duplicates
       * by converting the collection to a set. The component edges are then
       * mapped to the respective frames, so the global space of edges (outside of this task)
       * consists of unique tuples.
       */
      val EdgeSet = OverlappedPairsList.toSet
      val overlap = EdgeSet.map(x => ((p._1.metaData("FRAME"), x._1), (p._2.metaData("FRAME"), x._2)))

      val filtered = overlap.filter(entry => {
        val frameId1 = entry._1._1
        val compId1 = entry._1._2
        val compVals1 = AreaMinMaxTable(frameId1 + ":" + compId1)
        val fil1 = ((compVals1._1 >= 60.0) || (compVals1._1 < 60.0)) && ((compVals1._2 - compVals1._3) > 10.0)

        val frameId2 = entry._2._1
        val compId2 = entry._2._2
        val compVals2 = AreaMinMaxTable(frameId2 + ":" + compId2)
        val fil2 = ((compVals2._1 >= 60.0) || (compVals2._1 < 60.0)) && ((compVals2._2 - compVals2._3) > 10.0)
        fil1 && fil2
      })
      filtered
    })

    /**
     * ((String, Double), (String, Double))
     * TODO :: ((String, Double), Area, Min, Max), (String, Double) Area, Min, Max))
     * Collect the edges.
     * From the edge pairs collect all used vertices.
     * Repeated vertices are eliminated due to the set conversion.
     */
    val collectedEdges = componentFrameRDD.collect()
    val vertex = collectedEdges.flatMap(p => List(p._1, p._2)).toSet

    val k = new PrintWriter(new File("VertexList.txt"))
    k.write(vertex.toList.sortBy(p => p._1) + "\n")
    k.close()

    val p = new PrintWriter(new File("EdgeList.txt"))
    p.write(collectedEdges.toList.sorted + "\n")
    p.close()

    println("NUM VERTEX : " + vertex.size + "\n")
    println("NUM EDGES : " + collectedEdges.length + "\n")
    println(complete.toDebugString + "\n")
  }

  def checkCriteria(p: sciTensor): Boolean = {
    val hash = p.metaData
    val area = hash("AREA").toDouble
    val tempDiff = hash("DIFFERENCE").toDouble
    (area >= 60.0) || (area < 60.0) && (tempDiff > 10.0)
  }
}


