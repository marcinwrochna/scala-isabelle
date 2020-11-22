package de.unruh.isabelle.pure

import com.google.common.cache
import com.google.common.cache.{Cache, CacheBuilder, CacheLoader}
import com.google.common.collect.MapMaker
import de.unruh.isabelle.control.{Isabelle, OperationCollection}
import de.unruh.isabelle.misc.{FutureValue, Utils}
import de.unruh.isabelle.mlvalue.MLValue

import scala.annotation.compileTimeOnly
import scala.collection.mutable.ListBuffer
import scala.concurrent.ExecutionContext
import scala.language.experimental.macros
import scala.reflect
import scala.reflect.macros.whitebox
import scala.reflect.runtime
import scala.util.Random

// Implicits
import de.unruh.isabelle.mlvalue.Implicits._
import de.unruh.isabelle.pure.Implicits._

// DOCUMENT
object StringInterpolators extends OperationCollection {
  private case class Hole(varName: String, isTerm: Boolean)

  /** Best approximation to the union type of [[Term]] and [[Typ]] that we can easily manage. */
  type TermOrTyp = FutureValue with PrettyPrintable

  private class CommonMacroImpl(protected val c: whitebox.Context) {
    import c.universe._

    protected def stringOfLiteral(literal: c.Tree): String = literal match {
      case Literal(Constant(string: String)) => string
    }
    protected val prefix = s"VAR_${Utils.randomString()}_"
    protected val parts: List[String] = c.prefix.tree match {
      case Select(Apply(_, List(Apply(_, parts))), _) =>
        parts.map(stringOfLiteral)
    }
    protected val uniqueId: Long = Random.nextLong()
  }

  @compileTimeOnly("Macro implementation for StringInterpolators.TermInterpolator.term")
  private final class TermMacroImpl(_c: whitebox.Context) extends CommonMacroImpl(_c) {
    import c.universe._

    private val (templateString, holes) = {
      // TODO: buffer regexes
      val templateString = new StringBuilder
      val holes = new ListBuffer[Hole]
      var index = -1
      var nextHoleIsType = false
      for (part_ <- parts) {
        var part = part_
        if (index >= 0) {
          val isTerm =
            if (raw"%term\b.*".r.matches(part)) {
              part = part.stripPrefix("%term")
              true
            } else if (raw"%type\b.*".r.matches(part)) {
              part = part.stripPrefix("%type")
              false
            } else
              !nextHoleIsType

          val varName = (if (isTerm) "" else "'") + prefix + index.toString
          holes += Hole(varName = varName, isTerm = isTerm)
          templateString ++= " ?" ++= varName ++= ".0" += ' '
        }

        nextHoleIsType = raw".*(\b|\s)::\s*".r.matches(part)
        templateString ++= part
        index += 1
      }
      (templateString.toString, holes.toList)
    }

    c.info(c.enclosingPosition, s"For StringContext ${parts.mkString("•")}, template is $templateString and holes are $holes", force = true)

    def termApplyImpl(args: c.Expr[Any]*)
                     (context: c.Expr[Context], isabelle: c.Expr[Isabelle], executionContext: c.Expr[ExecutionContext]): c.Expr[Term] = {
      if (args.length != holes.length)
        c.abort(c.enclosingPosition, s"Expecting ${holes.length} arguments")

      val termInstantiations = for ((hole,term) <- holes.zip(args) if hole.isTerm)
        yield (hole.varName, q"$term : Term")
      val typInstantiations = for ((hole,typ) <- holes.zip(args) if !hole.isTerm)
        yield (hole.varName, q"$typ : Typ")

      c.Expr(
        q"""
          _root_.de.unruh.isabelle.pure.StringInterpolators.PrivateImplementation.termApplyImplRuntime(
             $uniqueId, $context, $templateString, List(..$typInstantiations), List(..$termInstantiations))($isabelle,$executionContext)
          """)
    }

    def termUnapplyImpl(term: c.Expr[Term])
                       (context: c.Expr[Context], isabelle: c.Expr[Isabelle], executionContext: c.Expr[ExecutionContext]):
    c.Expr[Option[Product]] = {
      val returnType = tq"(..${holes.map(h => if (h.isTerm) tq"Term" else tq"Typ")})"
      val vars = for (h <- holes) yield (c.universe.TermName(c.freshName("v")), h)

      c.Expr(q"""
          new {
            import _root_.de.unruh.isabelle.pure._
            def unapply(term : Term) : Option[$returnType] = {
              val listOption = StringInterpolators.PrivateImplementation.termUnapplyImplRuntime($uniqueId, $context, $templateString,
                   List(..${holes collect { case h if !h.isTerm => h.varName }}),
                   List(..${holes collect { case h if h.isTerm => h.varName }}),
                   term)
                   ($isabelle,$executionContext)
              listOption match {
                case None => None
                case Some((List(..${vars collect { case (v,h) if !h.isTerm => pq"$v" }}),
                           List(..${vars collect { case (v,h) if h.isTerm => pq"$v" }}))) => Some((..${vars.map(_._1)}))
                case _ => throw new AssertionError("Unexpected result in macro implementation of term-interpolation: " + listOption)
              }
            }
          }.unapply($term)
          """)
    }
  }

  @compileTimeOnly("Macro implementation for StringInterpolators.TypInterpolator.typ")
  private final class TypMacroImpl(_c: whitebox.Context) extends CommonMacroImpl(_c) {
    import c.universe._

    private val (templateString, varNames) = {
      val templateString = new StringBuilder
      val varNames = new ListBuffer[String]
      var index = -1
      for (part <- parts) {
        if (index >= 0) {
          val varName = "'" + prefix + index.toString
          varNames += varName
          templateString ++= " ?" ++= varName ++= ".0" += ' '
        }

        templateString ++= part
        index += 1
      }
      (templateString.toString, varNames.toList)
    }

    c.info(c.enclosingPosition, s"For StringContext ${parts.mkString("•")}, template is $templateString and holes are $varNames", force = true)

    def typApplyImpl(args: c.Expr[Typ]*)
                     (context: c.Expr[Context], isabelle: c.Expr[Isabelle], executionContext: c.Expr[ExecutionContext]): c.Expr[Typ] = {
      if (args.length != varNames.length)
        c.abort(c.enclosingPosition, s"Expecting ${varNames.length} arguments")

      val typInstantiations = for ((varName,typ) <- varNames.zip(args))
        yield (varName, q"$typ : Typ")

      c.Expr(
        q"""
          _root_.de.unruh.isabelle.pure.StringInterpolators.PrivateImplementation.typApplyImplRuntime(
             $uniqueId, $context, $templateString, List(..$typInstantiations))($isabelle,$executionContext)
          """)
    }

    def typUnapplySeqImpl(typ: c.Expr[Typ])
                         (context: c.Expr[Context], isabelle: c.Expr[Isabelle], executionContext: c.Expr[ExecutionContext]):
    c.Expr[Option[Seq[Typ]]] = c.Expr(q"""
        new _root_.de.unruh.isabelle.pure.StringInterpolators.PrivateImplementation.TypExtractorImplRuntime(
        $uniqueId, $context, $templateString, List(..$varNames)).unapplySeq($typ)
        """)
  }


  /** This object should be considered private. (It is only visible to be accessible in
   * macro code.) */
  object PrivateImplementation {
    // TODO: Probably we should just use Cache and uniqueId as keys.
    // TODO: Use softValues?
    private val termCache: Cache[Long, (Context,Term)] = CacheBuilder.newBuilder().weakValues().build[Long, (Context,Term)]()
    private val typCache: Cache[Long, (Context,Typ)] = CacheBuilder.newBuilder().weakValues().build[Long, (Context,Typ)]()

    private def cachedCompute[A](cache: Cache[Long, (Context,A)], uniqueId: Long, context: Context, compute: => A) = {
      val (prevContext, a) = cache.get(uniqueId, { () => (context, compute) })

      if (prevContext ne context) {
        val b = compute
        cache.put(uniqueId, (context, b))
        b
      } else
        a
    }

    private def parseTerm(uniqueId: Long, context: Context, string: String)
                         (implicit isabelle: Isabelle, executionContext: ExecutionContext): Term =
      cachedCompute(termCache, uniqueId, context,
        Term(Ops.setModePattern(context).retrieveNow, string))

    private def parseTyp(uniqueId: Long, context: Context, string: String)
                        (implicit isabelle: Isabelle, executionContext: ExecutionContext) : Typ =
      cachedCompute(typCache, uniqueId, context,
        Typ(Ops.setModePattern(context).retrieveNow, string))

    /** This function should be considered private. (It is only visible to be accessible in
     * macro code.) */
    def termUnapplyImplRuntime(uniqueId: Long, context: Context, string: String, typVars: List[String], termVars: List[String], term: Term)
                              (implicit isabelle: Isabelle, executionContext: ExecutionContext) : Option[(List[Typ], List[Term])] = {
      val template = parseTerm(uniqueId, context, string)
      Ops.patternMatch(context, template, term, typVars, termVars).retrieveNow
    }

    /** This class should be considered private. (It is only visible to be accessible in
     * macro code.) */
    final class TypExtractorImplRuntime(uniqueId: Long, context: Context, string: String, varNames: List[String])
                                       (implicit isabelle: Isabelle, executionContext: ExecutionContext) {
      def unapplySeq(typ: Typ) : Option[Seq[Typ]] = {
        val template = parseTyp(uniqueId, context, string)
        Ops.patternMatchTyp(context, template, typ, varNames).retrieveNow
      }
    }

    /** This function should be considered private. (It is only visible to be accessible in
     * macro code.) */
    def termApplyImplRuntime(uniqueId: Long, context: Context, string: String, typeInstantiation: List[(String,Typ)], termInstantiation: List[(String,Term)])
                            (implicit isabelle: Isabelle, executionContext: ExecutionContext): Cterm = {
      val template = parseTerm(uniqueId, context, string)
      // TODO: The ,0 could be done in ML
      val typeInstantiation2 = for ((v,typ) <- typeInstantiation) yield ((v,0), Ctyp(context, typ))
      val termInstantiation2 = for ((v,term) <- termInstantiation) yield ((v,0), Cterm(context, term))
      Ops.inferInstantiateTerm(context, typeInstantiation2, termInstantiation2, template).retrieveNow
    }

    /** This function should be considered private. (It is only visible to be accessible in
     * macro code.) */
    def typApplyImplRuntime(uniqueId: Long, context: Context, string: String, instantiation: List[(String,Typ)])
                           (implicit isabelle: Isabelle, executionContext: ExecutionContext) : Typ = {
      val template = parseTyp(uniqueId, context, string)
      // TODO: The ,0 could be done in ML
      val instantiation2 = for ((v,typ) <- instantiation) yield ((v,0), typ)
      Ops.instantiateTyp(instantiation2, template).retrieveNow
    }
  }

  // DOCUMENT
  implicit final class TermInterpolator(val stringContext: StringContext) {
    object term {
      def apply(args: TermOrTyp*)(implicit context: Context, isabelle: Isabelle, executionContext: ExecutionContext) : Term =
      macro TermMacroImpl.termApplyImpl

      // TODO: Can we add a fake unapplySeq that is erased on compilation and returns Option[List[TypAndTerm]]
      //  where TypAndTerm is a fake type that is the intersection of Typ and Term?
      //  This might give a better approximation of the behaviour of the interpolator for the IDE.
      //  (We'd need to hide unapply as well, maybe?)

      def unapply(term: Term)
                 (implicit context: Context, isabelle: Isabelle, executionContext: ExecutionContext): Option[Any] =
      macro TermMacroImpl.termUnapplyImpl
    }
  }

  // DOCUMENT
  implicit final class TypInterpolator(val stringContext: StringContext) {
    object typ {
      def apply(args: Typ*)(implicit context: Context, isabelle: Isabelle, executionContext: ExecutionContext) : Typ =
      macro TypMacroImpl.typApplyImpl

      def unapplySeq(typ: Typ)
                    (implicit context: Context, isabelle: Isabelle, executionContext: ExecutionContext): Option[Seq[Typ]] =
      macro TypMacroImpl.typUnapplySeqImpl
    }
  }

  //noinspection TypeAnnotation
  protected final class Ops(implicit isabelle: Isabelle, executionContext: ExecutionContext) {
    // TODO: This should be offered by Context
    val setModePattern = MLValue.compileFunction[Context, Context]("Proof_Context.set_mode Proof_Context.mode_pattern")
    val inferInstantiateTerm = MLValue.compileFunction[Context, List[((String, Int), Typ)], List[((String, Int), Cterm)], Term, Cterm](
      """fn (ctxt, typInst, termInst, term) => let
        |  val term2 = Term.map_types (Term.map_atyps (fn v as TVar(ni,_) =>
        |        (case AList.lookup (op=) typInst ni of SOME T => T | NONE => v) | T => T)) term
        |  val thm1 = infer_instantiate ctxt [(("x",0), Thm.cterm_of ctxt term2)] reflexive_thm
        |  val thm2 = infer_instantiate ctxt termInst thm1
        |  val term = Thm.rhs_of thm2
        |  in term end
        |""".stripMargin)
    val patternMatch = MLValue.compileFunction[Context, Term, Term, List[String], List[String], Option[(List[Typ],List[Term])]](
      """fn (ctxt,pattern,term,typVars, termVars) => let
        |  val (tyenv,tenv) = Pattern.match (Proof_Context.theory_of ctxt) (pattern,term)
        |                (Vartab.empty, Vartab.empty)
        |  val typMatch = map (fn x => Vartab.lookup tyenv (x,0) |> the |> snd) typVars
        |  val termMatch = map (fn x => Vartab.lookup tenv (x,0) |> the |> snd) termVars
        |  in SOME (typMatch,termMatch) end
        |  handle Pattern.MATCH => NONE""".stripMargin)
    val instantiateTyp = MLValue.compileFunction[List[((String, Int), Typ)], Typ, Typ](
      """fn (inst, typ) => Term.map_atyps (fn v as TVar(ni,_) =>
        |        (case AList.lookup (op=) inst ni of SOME T => T | NONE => v) | T => T) typ""".stripMargin)
    val patternMatchTyp = MLValue.compileFunction[Context, Typ, Typ, List[String], Option[List[Typ]]](
      """fn (ctxt,pattern,typ,vars) => let
        |  val tyenv = Sign.typ_match (Proof_Context.theory_of ctxt) (pattern,typ) Vartab.empty
        |  val match = map (fn x => Vartab.lookup tyenv (x,0) |> the |> snd) vars
        |  in SOME match end
        |  handle Type.TYPE_MATCH => NONE""".stripMargin
    )
  }

  override protected def newOps(implicit isabelle: Isabelle, ec: ExecutionContext): Ops = new Ops
}