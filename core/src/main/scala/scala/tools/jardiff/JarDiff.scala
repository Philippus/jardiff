/*
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbenc.com>
 */

package scala.tools.jardiff

import java.io.{File, OutputStream}
import java.nio.file._

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.treewalk.EmptyTreeIterator

import scala.tools.jardiff.JGitUtil._

final class JarDiff(files: List[List[Path]], config: JarDiff.Config, renderers: String => List[FileRenderer]) {
  private val targetBase = config.gitRepo.getOrElse(Files.createTempDirectory("jardiff-"))

  def diff(): Boolean = {
    var differenceFound = false
    import org.eclipse.jgit.api.Git
    val git: Git =
      Git.init.setDirectory(targetBase.toFile).call

    def renderAndCommit(fs: List[Path]): RevCommit = {
      git.rm().setCached(true).addFilepattern(".")

      for (f <- fs) {
        val root = IOUtil.rootPath(f)
        if (Files.isDirectory(root))
          renderFiles(root)
        else
          renderFile(f.getParent)(f, targetBase.resolve(f.getFileName))
      }

      git.add().addFilepattern(".").call()
      git.commit().setMessage("jardiff textified output of: " + fs.mkString(File.pathSeparator)).call()
    }
    files match {
      case head :: Nil =>
        val commit = renderAndCommit(head)
        printInitialDiff(git, commit)
      case _ =>
        val commits = files.iterator.map(renderAndCommit)
        commits.sliding(2).foreach {
          case Seq(commit1, commit2) =>
            differenceFound ||= printDiff(git, commit1, commit2)
        }
    }


    if (config.gitRepo.isEmpty)
      IOUtil.deleteRecursive(targetBase)

    differenceFound
  }

  private def printDiff(git: Git, commit1: RevCommit, commit2: RevCommit): Boolean = {
    val cmd = git.diff()
    val diffFormatter = new DiffFormatter(config.diffOutputStream)
    config.contextLines.foreach{lines => cmd.setContextLines(lines); diffFormatter.setContext(lines)}
    cmd.setOldTree(getCanonicalTreeParser(git, commit1))
    cmd.setNewTree(getCanonicalTreeParser(git, commit2))
    val diffEntries = cmd.call()
    diffFormatter.setRepository(git.getRepository)
    diffFormatter.format(diffEntries)
    diffEntries.size() > 0
  }
  private def printInitialDiff(git: Git, initialCommit: RevCommit): Unit = {
    val cmd = git.diff()
    val diffFormatter = new DiffFormatter(config.diffOutputStream)
    config.contextLines.foreach{lines => cmd.setContextLines(lines); diffFormatter.setContext(lines)}
    cmd.setOldTree(new EmptyTreeIterator())
    cmd.setNewTree(getCanonicalTreeParser(git, initialCommit))
    val diffEntries = cmd.call()
    diffFormatter.setRepository(git.getRepository)
    diffFormatter.format(diffEntries)
  }

  private def renderFiles(sourceBase: java.nio.file.Path) = {
    IOUtil.mapRecursive(sourceBase, targetBase)(renderFile(sourceBase))
  }

  private def renderFile(sourceBase: Path)(sourceFile: Path, targetFile: Path) = {
    val ix = sourceFile.getFileName.toString.lastIndexOf(".")
    val extension = if (ix >= 0) sourceFile.getFileName.toString.substring(ix + 1) else ""
    if (!Files.isSymbolicLink(sourceFile)) {
      for (renderer <- renderers(extension)) {
        val outPath = targetFile.resolveSibling(targetFile.getFileName + renderer.outFileExtension)
        renderer.render(sourceFile, outPath)
      }
    }
  }
}

object JarDiff {
  def expandClassPath(f: String) = {
    val path = Paths.get(f)
    if (Files.exists(path)) List(path)
    else if (f.indexOf(java.io.File.pathSeparatorChar) != -1)
      f.split(java.io.File.pathSeparatorChar).toList.map(s => Paths.get(s))
    else List(path)
  }
  def apply(files: List[List[Path]], config: JarDiff.Config): JarDiff = {
    val renderers = Map("class" -> List(new AsmTextifyRenderer(config.code), new ScalapRenderer())).withDefault(_ => List(IdentityRenderer))
    new JarDiff(files, config, renderers)
  }

  case class Config(gitRepo: Option[Path], code: Boolean, contextLines: Option[Int], diffOutputStream: OutputStream)

}
