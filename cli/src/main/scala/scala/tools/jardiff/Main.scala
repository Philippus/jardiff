/*
 * Copyright (C) Lightbend Inc. <https://www.lightbend.com>
 */

package scala.tools.jardiff

import java.io.File
import java.nio.file._

import org.eclipse.jgit.util.io.NullOutputStream
import scala.util.control.NonFatal

import org.rogach.scallop.ScallopConf

object Main {
  def main(args: Array[String]): Unit = {
    run(args) match {
      case ShowUsage(msg) => System.err.println(msg); sys.exit(-1)
      case Error(err) => err.printStackTrace(System.err); sys.exit(-1)
      case Success(diffFound) => sys.exit(if (diffFound) 1 else 0)
    }
  }
  class Conf(args: Seq[String]) extends ScallopConf(args) {
    banner(s"""usage: jardiff [-c] [-g <dir>] [-h] [-i <arg>] [-p] [-q] [-r] [-U <n>] VERSION1 [VERSION2 ...]

Each VERSION may designate a single file, a directory, JAR file or a `${File.pathSeparator}`-delimited classpath
""".stripMargin)

    val NoCode = opt[Boolean]("suppress-code", short = 'c', descr = "Suppress method bodies")
    val Git = opt[String]("git", short = 'g', descr = "Directory to output a git repository containing the diff", argName = "dir")
    val Help = opt[Boolean]("help", short = 'h', descr = "Display this message")
    val Ignore = opt[List[String]]("ignore", short = 'i', descr = "File pattern to ignore rendered files in gitignore format")
    val NoPrivates = opt[Boolean]("suppress-privates", short = 'p', "Display only non-private members")
    val Quiet = opt[Boolean]("quiet", short = 'q', descr = "Don't output diffs to standard out")
    val Raw = opt[Boolean]("raw", short = 'r', descr = "Disable sorting and filtering of classfile contents")
    val ContextLines = opt[Int]("unified", short = 'U', descr = "Number of context lines in diff", argName = "n")

    val Versions = trailArg[List[String]](required = false)
    verify()
//    mainOptions.map(x => x.shortNames)
  }

  private def helpText: String = new Conf(Seq.empty).helpFormatter.getFullHelpString()

  def run(args: Array[String]): RunResult = {
    val conf = new Conf(args)
    try {
      if (conf.Help.isSupplied) {
        ShowUsage(helpText)
      } else {
        val gitRepo = if (conf.Git.isSupplied) Some(Paths.get(conf.Git.getOrElse(""))) else None
        val diffOutputStream = if (conf.Quiet.isSupplied) NullOutputStream.INSTANCE else System.out
        val config = JarDiff.Config(gitRepo, !conf.NoCode.isSupplied, conf.Raw.isSupplied,
          !conf.NoPrivates.isSupplied, conf.ContextLines.toOption, diffOutputStream,
          conf.Ignore.getOrElse(List.empty[String])
        )
        val paths = conf.Versions.getOrElse(List.empty).map(JarDiff.expandClassPath)
        paths match {
          case Nil => ShowUsage(helpText)
          case _ =>
            val jarDiff = JarDiff(paths, config)
            val diffFound = jarDiff.diff()
            Success(diffFound)
        }
      }
    } catch {
      case NonFatal(t) => Error(t)
    }
  }
}

sealed abstract class RunResult
case class ShowUsage(msg: String) extends RunResult
case class Error(err: Throwable) extends RunResult
case class Success(diffFound: Boolean) extends RunResult
