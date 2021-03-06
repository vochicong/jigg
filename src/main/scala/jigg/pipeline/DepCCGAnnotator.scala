package jigg.pipeline

/*
 Copyright 2013-2017 Hiroshi Noji

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 */

import java.io.{ByteArrayInputStream, File}
import java.util.Properties

import scala.xml._
import scala.sys.process.Process

import jigg.util.PropertiesUtil
import jigg.util.XMLUtil.RichNode

class DepCCGAnnotator(override val name: String, override val props: Properties)
    extends AnnotatingSentencesInParallel { self =>

  @Prop(gloss = "Path to run.py", required = true) var path = ""
  @Prop(gloss = "Path to the model (e.g., tri_headfirst directory)", required = true) var model = ""
  @Prop(gloss = "Language (en|ja)") var lang = "en"
  @Prop(gloss = "If true, launch multiple depccgs for parallel parsing. See -help depccg for more details.") var parallel = false
  readProps()

  override def nThreads = if (parallel) super.nThreads else 1

  override def description = s"""${super.description}

  A wrapper for depccg (https://github.com/masashi-y/depccg). -${name}.path (path to the
  main script) and ${name}.model (path to the model directory) are two necessary
  arguments.

  One complication is that DepCCG originally supports parallel parsing in itself but it
  depends on how a user compiles the code. Speficially, DepCCG can be run in parallel
  only when it is compiled with OpenMP.

  The option -${name}.parallel is for alleviating this complication. If it is false
  (default), Jigg does launch only single instance of depccg, which means parallelism
  is completely delegated to depccg itself.

  With "-${name}.parallel true", parallelism is managed at Jigg side. This will launches
  many different depccg instances, and distribute the inputs into them. We assume this
  option will only be used when depccg is built without OpenMP. Since this option has
  many overheads in particular longer model loading and large memory consumption, we
  recommend to use "OpenMPed" depccg with "-${name}.parallel false".

"""

  checkArgument()

  def checkArgument() = {
    if (!path.endsWith("run.py") || !new File(path).exists) argumentError("path",
      s"Something wrong in -${name}.path. That should be the path to run.py")

    if (!new File(model).isDirectory || !new File(model, "tagger_model").exists)
      argumentError("model", s"-${name}.model seems incorrect. That should points to a directory containing tagger_model, cat_dict.txt, etc.")
  }

  def mkLocalAnnotator = new LocalDepCCGAnnotator

  class LocalDepCCGAnnotator extends LocalAnnotator {

    override def annotate(annotation: Node) = {
      assert(annotation.label == "sentences")

      val sentences = annotation.child

      val input = sentences.map(mkInput).mkString("\n")
      val result = run(input)

      // result is given by candc-style xml
      val resultNode = XML.loadString(result.mkString("\n"))

      val ccgs = resultNode \\ "ccg"
      assert(ccgs.size == sentences.size)

      val newSentences = sentences zip ccgs map {
        case (s, c) => CandCAnnotator.annotateCCGSpans(s, c, name)
      }

      annotation.asInstanceOf[Elem].copy(child = newSentences)
    }

    // Input looks like "This|X|X is|X|X ..."
    def mkInput(sentence: Node): String = {
      val forms = (sentence \\ "token") map (_ \@ "form")
      forms map (_ + "|X|X") mkString " "
    }

    def run(input: String): Stream[String] =
      (cmd #< new ByteArrayInputStream(input.getBytes("UTF-8"))).lineStream_!

    val cmd = Process(s"python ${path} --input-format POSandNERtagged --format xml ${model} ${lang}")

  }

  override def requires = lang match {
    case "en" => Set(Requirement.Ssplit, Requirement.Tokenize)
    case "ja" => Set(Requirement.Ssplit, JaRequirement.TokenizeWithIPA)
  }

  override def requirementsSatisfied = Set(Requirement.CCGDerivation)
}
