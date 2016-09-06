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

package org.apache.spark.h2o.converters


import java.lang.reflect.Constructor

import org.apache.spark.h2o.H2OConf
import org.apache.spark.h2o.utils.ReflectionUtils
import org.apache.spark.rdd.RDD
import org.apache.spark.{Partition, SparkContext, TaskContext}
import water.fvec.Frame

import scala.language.postfixOps
import scala.reflect.ClassTag
import scala.reflect.runtime.universe._
import ReflectionUtils._

/**
  * Convert H2OFrame into an RDD (lazily).
  *
  * @param frame  an instance of H2O frame
  * @param colNamesInResult names of columns
  * @param sc  an instance of Spark context
  * @tparam A  type for resulting RDD
  * @tparam T  specific type of H2O frame
  */
private[spark]
class H2ORDD[A <: Product: TypeTag: ClassTag, T <: Frame] private(@transient val frame: T,
                                                                  val colNamesInResult: Array[String])
                                                                 (@transient sc: SparkContext)
  extends {
    override val isExternalBackend = H2OConf(sc).runsInExternalClusterMode
  } with RDD[A](sc, Nil) with H2ORDDLike[T] {

  // Get column names before building an RDD
  def this(@transient fr : T)
          (@transient sc: SparkContext) = this(fr, fieldNamesOf[A])(sc)

  // Check that H2OFrame & given Scala type are compatible
  if (colNamesInResult.length > 1) {
    colNamesInResult.foreach { name =>
      if (frame.find(name) == -1) {
        throw new IllegalArgumentException("Scala type has field " + name +
          " but H2OFrame does not have a matching column; has " + frame.names().mkString(","))
      }
    }
  }

  /** Number of columns in the full dataset */
  val numColsInFrame = frame.numCols()

  val colNamesInFrame = frame.names()

  // the following two lines are for some future development;
  // they can as well be uncommented when needed
//  val types = ReflectionUtils.types[A](colNamesInResult)
//  val expectedTypesAll: Option[Array[Byte]] = ConverterUtils.prepareExpectedTypes(isExternalBackend, types)

  /**
   * :: DeveloperApi ::
   * Implemented by subclasses to compute a given partition.
   */
  override def compute(split: Partition, context: TaskContext): Iterator[A] = {
    val iterator = new H2ORDDIterator(frameKeyName, split.index)
    ConverterUtils.getIterator[A](isExternalBackend, iterator)
  }

  /**
    * Names of types are used to refer the readers, which are indexed by types
    */
  private lazy val columnTypeNames = typeNamesOf[A](colNamesInResult)

  private val jc = implicitly[ClassTag[A]].runtimeClass

  private def columnReaders(rcc: ReadConverterContext) = columnTypeNames map rcc.readerMap

  private def opt[X](op: => Any): Option[X] = try {
    Option(op.asInstanceOf[X])
  } catch {
    case ex: Exception =>
      println(ex)
      None
  }
  // maps data columns to product components
  val columnMapping: Array[Int] =
    if (columnTypeNames.length == 1) Array(0) else multicolumnMapping

  def multicolumnMapping: Array[Int] = {
    try {
      val mappings: Array[Int] = colNamesInResult map (colNamesInFrame indexOf _)

      val bads = mappings.zipWithIndex collect {
        case (j, at) if j < 0 =>
          if (at < colNamesInResult.length) colNamesInResult(at) else s"[[$at]] (column of type ${columnTypeNames(at)}"
      }

      if (bads.nonEmpty) {
        throw new scala.IllegalArgumentException(s"Missing columns: ${bads mkString ","}")
      }

      mappings
    }
  }

  class H2ORDDIterator(val keyName: String, val partIndex: Int) extends H2OChunkIterator[A] {

    override lazy val converterCtx: ReadConverterContext =
      ConverterUtils.getReadConverterContext(keyName,
        partIndex)

    private lazy val readers = columnReaders(converterCtx)

    private val convertPerColumn: Array[() => AnyRef] =
      (columnMapping zip readers) map
        { case (j, r) => () =>
          r.apply(j).asInstanceOf[AnyRef]  // this last trick converts primitives to java.lang wrappers
        }

    def extractRow: Array[AnyRef] = {
      val rowOpt = convertPerColumn map (_())
      converterCtx.increaseRowIdx()
      rowOpt
    }

    private var hd: Option[A] = None
    private var total = 0

    /**
      * Checks if there's a next value in the stream.
      * Note that reading does not always lead to a success,
      * so there are the following options:
      * - the previous read succeeded, and we have a cached value in hd
      * then we are fine, no further actions
      * - there is no cached value, and the underlying iterator says it has more
      * in this case we try to read the row. This may succeed or not
      * - if it succeeds, we are good
      * - if it does not succeed, it probably means the value is no good (e.g. an exception thrown when
      * we try to read a string column as a Double, etc;
      * in this case we repeat the attempt - until either we receive a good value, or we are out of rows
      * @return
      */
    override def hasNext = {
      while (hd.isEmpty && super.hasNext) {
        hd = readOne()
        total += 1
      }
      hd.isDefined
    }

    def next(): A = {
      if (hasNext) {
        val a = hd.get
        hd = None
        a
      } else {
        throw new NoSuchElementException(s"No more elements in this iterator: found $total  out of ${converterCtx.numRows}")
            }
      }

    /**
      * This function takes a row of raw data (array of Objects) and transforms it
      * to a value of type A (if possible).
      * extractRow gives us the array (if succeeds)
      * On this array it tries to apply a builder that accepts that data.
      * A Builder has a constructor inside.
      * If exactly one attempt to build an instance succeeds, we are good.
      * If no attempts succeeded, we return a None.
      * If more than one constructor produced an instance, it means we are out of luck,
      * there are too many constructors for this kind of data; and an exception is thrown.
      *
      * @return Option[A], that is Some(result:A), if succeeded, or else None
      */
      private def readOne(): Option[A] = {
            val data = extractRow

            val res: Seq[A] = for {
              builder <- builders
              instance <- builder(data)
            } yield instance

            res.toList match {
              case Nil => None
              case unique :: Nil => Option(unique)
              case one :: two :: more =>
                throw new scala.IllegalArgumentException(
                                         s"found more than one $jc constructor for given args - can't choose")
          }
        }

  }


  lazy val constructors: Seq[Constructor[_]] = {

    val cs = jc.getConstructors
    val found = cs.collect {
      case c if c.getParameterTypes.length == colNamesInResult.length => c
    }

    if (found.isEmpty) throw new scala.IllegalArgumentException(
      s"Constructor must take exactly ${colNamesInResult.length} args")

    found
  }

  private case class Builder(c:  Constructor[_]) {
    def apply(data: Array[AnyRef]): Option[A] = {
      val x = opt(c.newInstance(data:_*).asInstanceOf[A])
      x
    }
  }

  private lazy val builders = constructors map Builder

}
