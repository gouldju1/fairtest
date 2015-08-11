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

package org.apache.spark.sql


case class FunctionResult(f1: String, f2: String)

class UDFSuite extends QueryTest {

  private lazy val ctx = org.apache.spark.sql.test.TestSQLContext
  import ctx.implicits._

  test("built-in fixed arity expressions") {
    val df = ctx.emptyDataFrame
    df.selectExpr("rand()", "randn()", "rand(5)", "randn(50)")
  }

  test("built-in vararg expressions") {
    val df = Seq((1, 2)).toDF("a", "b")
    df.selectExpr("array(a, b)")
    df.selectExpr("struct(a, b)")
  }

  test("built-in expressions with multiple constructors") {
    val df = Seq(("abcd", 2)).toDF("a", "b")
    df.selectExpr("substr(a, 2)", "substr(a, 2, 3)").collect()
  }

  test("count") {
    val df = Seq(("abcd", 2)).toDF("a", "b")
    df.selectExpr("count(a)")
  }

  test("count distinct") {
    val df = Seq(("abcd", 2)).toDF("a", "b")
    df.selectExpr("count(distinct a)")
  }

  test("error reporting for incorrect number of arguments") {
    val df = ctx.emptyDataFrame
    val e = intercept[AnalysisException] {
      df.selectExpr("substr('abcd', 2, 3, 4)")
    }
    assert(e.getMessage.contains("arguments"))
  }

  test("error reporting for undefined functions") {
    val df = ctx.emptyDataFrame
    val e = intercept[AnalysisException] {
      df.selectExpr("a_function_that_does_not_exist()")
    }
    assert(e.getMessage.contains("undefined function"))
  }

  test("Simple UDF") {
    ctx.udf.register("strLenScala", (_: String).length)
    assert(ctx.sql("SELECT strLenScala('test')").head().getInt(0) === 4)
  }

  test("ZeroArgument UDF") {
    ctx.udf.register("random0", () => { Math.random()})
    assert(ctx.sql("SELECT random0()").head().getDouble(0) >= 0.0)
  }

  test("TwoArgument UDF") {
    ctx.udf.register("strLenScala", (_: String).length + (_: Int))
    assert(ctx.sql("SELECT strLenScala('test', 1)").head().getInt(0) === 5)
  }

  test("UDF in a WHERE") {
    ctx.udf.register("oneArgFilter", (n: Int) => { n > 80 })

    val df = ctx.sparkContext.parallelize(
      (1 to 100).map(i => TestData(i, i.toString))).toDF()
    df.registerTempTable("integerData")

    val result =
      ctx.sql("SELECT * FROM integerData WHERE oneArgFilter(key)")
    assert(result.count() === 20)
  }

  test("UDF in a HAVING") {
    ctx.udf.register("havingFilter", (n: Long) => { n > 5 })

    val df = Seq(("red", 1), ("red", 2), ("blue", 10),
      ("green", 100), ("green", 200)).toDF("g", "v")
    df.registerTempTable("groupData")

    val result =
      ctx.sql(
        """
         | SELECT g, SUM(v) as s
         | FROM groupData
         | GROUP BY g
         | HAVING havingFilter(s)
        """.stripMargin)

    assert(result.count() === 2)
  }

  test("UDF in a GROUP BY") {
    ctx.udf.register("groupFunction", (n: Int) => { n > 10 })

    val df = Seq(("red", 1), ("red", 2), ("blue", 10),
      ("green", 100), ("green", 200)).toDF("g", "v")
    df.registerTempTable("groupData")

    val result =
      ctx.sql(
        """
         | SELECT SUM(v)
         | FROM groupData
         | GROUP BY groupFunction(v)
        """.stripMargin)
    assert(result.count() === 2)
  }

  test("UDFs everywhere") {
    ctx.udf.register("groupFunction", (n: Int) => { n > 10 })
    ctx.udf.register("havingFilter", (n: Long) => { n > 2000 })
    ctx.udf.register("whereFilter", (n: Int) => { n < 150 })
    ctx.udf.register("timesHundred", (n: Long) => { n * 100 })

    val df = Seq(("red", 1), ("red", 2), ("blue", 10),
      ("green", 100), ("green", 200)).toDF("g", "v")
    df.registerTempTable("groupData")

    val result =
      ctx.sql(
        """
         | SELECT timesHundred(SUM(v)) as v100
         | FROM groupData
         | WHERE whereFilter(v)
         | GROUP BY groupFunction(v)
         | HAVING havingFilter(v100)
        """.stripMargin)
    assert(result.count() === 1)
  }

  test("struct UDF") {
    ctx.udf.register("returnStruct", (f1: String, f2: String) => FunctionResult(f1, f2))

    val result =
      ctx.sql("SELECT returnStruct('test', 'test2') as ret")
        .select($"ret.f1").head().getString(0)
    assert(result === "test")
  }

  test("udf that is transformed") {
    ctx.udf.register("makeStruct", (x: Int, y: Int) => (x, y))
    // 1 + 1 is constant folded causing a transformation.
    assert(ctx.sql("SELECT makeStruct(1 + 1, 2)").first().getAs[Row](0) === Row(2, 2))
  }

  test("type coercion for udf inputs") {
    ctx.udf.register("intExpected", (x: Int) => x)
    // pass a decimal to intExpected.
    assert(ctx.sql("SELECT intExpected(1.0)").head().getInt(0) === 1)
  }
}