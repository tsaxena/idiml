package com.idibon.ml.app

import com.idibon.ml.common.Engine

/**
  * Created by stefankrawczyk on 1/29/16.
  */
class TestApp {
}
object TestApp extends Tool {

  private [this] val _dispatcher = Map(
    ("run" -> QuickTrainAndPredict)
  )

  def run(engine: Engine, argv: Array[String]) {
    println("Usage: App TOOL [options]")
    println(s"TOOL should be one of ${_dispatcher.keys}")
  }

  def main(argv: Array[String]) = {
    /* ensure that a dummy argument is provided in the case of no
     * provided command line arguments, so that the Arrays#copyOfRange
     * call below doesn't exception-out */
    val arguments = if (argv.length == 0) Array[String]("") else argv

    val engine = new com.idibon.ml.common.EmbeddedEngine

    _dispatcher.getOrElse(arguments(0), this)
      .run(engine, java.util.Arrays.copyOfRange(arguments, 1, arguments.length))
  }
}
