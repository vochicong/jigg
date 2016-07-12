package jigg.pipeline

/*
 Copyright 2013-2016 Hiroshi Noji

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

import jigg.util.PropertiesUtil
import jigg.util.{IOUtil, XMLUtil}

import java.util.Properties
import java.io.File

import scala.collection.mutable.ArrayBuffer
import scala.sys.process.Process
import scala.xml._

trait SyntaxNetAnnotator extends Annotator {

  @Prop(gloss = "") var path = "./syntaxnet"
  @Prop(gloss = "") var model = ""

  readProps()

  // check the correctness of path
  override def init() = {

    def wrongPath() =
      argumentError("path", s"$path is not the correct path to the syntaxnet.")

    val root = new File(path)

    root.listFiles match {
      case null => wrongPath()
      case files =>
        if (!files.contains(new File(root, "bazel-bin"))
          || !files.contains(new File(root, "syntaxnet"))) wrongPath()
    }
  }

  lazy val modelPath = model match {
    case "" =>
      System.err.println("No model path is given for syntaxnet. Defaulting to the parsey_mcparseface model.")
      "syntaxnet/models/parsey_mcparseface" // Note this is relative path from `path`.
    case _ => model
  }

  // Aggrate all `sentence` elements across documents, and run the syntaxnet
  // on them in a batch manner.
  override def annotate(annotation: Node): Node = {

    val documentSeq = annotation \\ "document"

    val sentences: Seq[Node] = documentSeq map { d => (d \ "sentences").head }
    val sentenceSeqs = sentences map (_ \ "sentence")
    val sentenceSeq = sentenceSeqs.flatten

    val offsets = sentenceSeqs.map(_.size).scanLeft(0)(_+_)

    val input = java.io.File.createTempFile("jigg", null)
    input.deleteOnExit()

    IOUtil.writing(input.getAbsolutePath) { w =>
      for (sentence <- sentenceSeq) {
        w.write(toCoNLL(sentence) + "\n\n")
      }
    }

    // toIterator is intented to safe the memory (with a bit sacrifice of speed?)
    val out = run(input.getAbsolutePath).toIterator

    var i = 0
    val annotatedSentences = new ArrayBuffer[Node]
    val tokens = new ArrayBuffer[Seq[String]] // not segmented on documents

    def addAnnotation() = annotatedSentences += annotateSentence(tokens, sentenceSeq(i))

    for (line <- out) line match {
      case "" =>
        if (!tokens.isEmpty) addAnnotation()
        tokens.clear
        i += 1
      case _ =>
        tokens += line.split("\t")
    }
    if (!tokens.isEmpty) addAnnotation()

    val newDocumentSeq: Seq[Node] = (0 until documentSeq.size) map { docidx =>
      val begin = offsets(docidx)
      val end = offsets(docidx + 1)
      val newSentenceSeqInDoc = (begin until end) map (annotatedSentences)
      val newSentences = XMLUtil.replaceChild(sentences(docidx), newSentenceSeqInDoc)

      XMLUtil.addOrOverrideChild(documentSeq(docidx), newSentences)
    }
    XMLUtil.replaceChild(annotation, newDocumentSeq)
  }

  // internal method
  protected def toCoNLL(sentence: Node): String

  protected def annotateSentence(conll: Seq[Seq[String]], sentence: Node): Node

  protected def run(input: String): Stream[String]

  protected def posCmd = makeCmd("tagger-params", "brain_tagger", "64")
  protected def parserCmd = makeCmd("parser-params", "brain_parser", "512,512")

  private def makeCmd(param: String, prefix: String, hidden: String) = {
    val bin = new File("bazel-bin/syntaxnet/parser_eval")
    Process(s"""${bin} --input=stdin-conll --output=stdout-conll \\
--hidden_layer_sizes=${hidden} --arg_prefix=${prefix} --graph_builder=structured \\
--task_context=${modelPath}/context.pbtxt --model_path=${modelPath}/${param} \\
--slim_model --batch_size=1024""", Some(new File(path)))
  }
}

object SyntaxNetAnnotator {

  def emptyCoNLL(sentence: Node): String =
    (sentence \ "tokens" \\ "token").zipWithIndex map { case (token, i) =>
      val idx = i + 1
      val form = token \@ "form"
      s"$idx\t$form\t_\t_\t_\t_\t_\t_"
    } mkString "\n"

  def taggedCoNLL(sentence: Node): String =
    (sentence \ "tokens" \\ "token").zipWithIndex map { case (token, i) =>
      val idx = i + 1
      val form = token \@ "form"
      val pos = token \@ "pos"
      // val cpos = token \@ "cpos"
      s"$idx\t$form\t_\t_\t$pos\t_\t_\t_"
    } mkString "\n"

  def POSAnnotatedTokens(conll: Seq[Seq[String]], tokens: Node, name: String): Node = {
    val tokenSeq = tokens \\ "token"
    val newTokenSeq = tokenSeq zip conll map { case (tokenNode, token) =>
      val pos = token(4)
      val cpos = token(3)
      XMLUtil.addAttributes(tokenNode, Map("pos"-> pos, "cpos"->cpos))
    }
    val nameAdded = XMLUtil.addAnnotatorName(tokens, name)
    XMLUtil.replaceChild(nameAdded, newTokenSeq)
  }

  def parseAnnotation(conll: Seq[Seq[String]], tokens: Node, name: String): Node = {
    val tokenSeq = tokens \\ "token"
    val tokenId = tokenSeq map (_ \@ "id")

    val heads = conll.map(_(6).toInt - 1)
    val label = conll.map(_(7))
    val headId = heads map {
      case -1 => "ROOT"
      case idx => tokenId(idx)
    }
    val depNodeSeq = (0 until headId.size) map { i =>
      <dependency id={Annotation.Dependency.nextId} head={ headId(i) } dependent={ tokenId(i) } deprel={ label(i) }/>
    }
    <dependencies type="basic" annotators={ name }>{ depNodeSeq }</dependencies>
  }
}

class SyntaxNetPOSAnnotator(override val name: String, override val props: Properties)
    extends SyntaxNetAnnotator {

  def toCoNLL(sentence: Node): String = SyntaxNetAnnotator.emptyCoNLL(sentence)

  def annotateSentence(conll: Seq[Seq[String]], sentence: Node): Node = {
    val tokens = (sentence \ "tokens").head
    val newTokens = SyntaxNetAnnotator.POSAnnotatedTokens(conll, tokens, name)
    XMLUtil.addOrOverrideChild(sentence, newTokens)
  }

  def run(input: String) = (Process(s"cat $input") #| posCmd).lineStream_!

  override def requires = Set(Requirement.Tokenize)
  override def requirementsSatisfied = Set(Requirement.POS)
}

// Use this trait if one wants to keep the already annotated POS tags
// (by other annotators).
class SyntaxNetParseAnnotator(override val name: String, override val props: Properties)
    extends SyntaxNetAnnotator {

  def toCoNLL(sentence: Node): String = SyntaxNetAnnotator.taggedCoNLL(sentence)

  def annotateSentence(conll: Seq[Seq[String]], sentence: Node): Node = {
    val tokens = (sentence \ "tokens").head
    val parse = SyntaxNetAnnotator.parseAnnotation(conll, tokens, name)
    XMLUtil.addChild(sentence, parse)
  }

  def run(input: String) = (Process(s"cat $input") #| parserCmd).lineStream_!

  override def requires = Set(Requirement.POS)
  override def requirementsSatisfied = Set(Requirement.Parse)
}

class SyntaxNetFullAnnotator(override val name: String, override val props: Properties)
    extends SyntaxNetAnnotator {

  def toCoNLL(sentence: Node): String = SyntaxNetAnnotator.emptyCoNLL(sentence)

  def annotateSentence(conll: Seq[Seq[String]], sentence: Node): Node = {
    val tokens = (sentence \ "tokens").head
    val newTokens = SyntaxNetAnnotator.POSAnnotatedTokens(conll, tokens, name)
    val parse = SyntaxNetAnnotator.parseAnnotation(conll, tokens, name)

    val tokenUpdated = XMLUtil.addOrOverrideChild(sentence, newTokens)
    XMLUtil.addChild(tokenUpdated, parse)
  }

  def run(input: String) = (Process(s"cat $input") #| posCmd #| parserCmd).lineStream_!

  override def requires = Set(Requirement.Tokenize)
  override def requirementsSatisfied = Set(Requirement.POS, Requirement.Parse)
}
