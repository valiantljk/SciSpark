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
package org.dia.TRMMUtils

import java.text.SimpleDateFormat
import java.util.Calendar

import org.dia.sLib.FileUtils

/**
 * Creates a file of random number of lines.
 */
object RandomDatesGenerator {

  var numDates = 100
  var fileName = "/tmp/urls"
  var sb: StringBuilder = new StringBuilder

  def main(args: Array[String]) {
    if (args.length >= 1)
      numDates = args(0).toInt
    if (args.length >= 2)
      fileName = args(1)

    var c: Calendar = Calendar.getInstance()
    var sdf: SimpleDateFormat = new SimpleDateFormat("yyyy-MM-dd")
    c.setTime(sdf.parse("0000-01-01"));

    println("Generating dates . . .")
    (1 to numDates).foreach(e => {
      c.add(Calendar.DATE, 1)
      sb.append(sdf.format(c.getTime())).append("\n")
    })
    println("Writing file . . . ")
    FileUtils.appendToFile(fileName, sb.toString())
    println("DONE!")
  }

}