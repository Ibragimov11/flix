/*
 * Copyright 2017 Magnus Madsen
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ca.uwaterloo.flix.language.phase

import ca.uwaterloo.flix.api.Flix
import ca.uwaterloo.flix.language.CompilationError
import ca.uwaterloo.flix.language.ast.TypedAst._
import ca.uwaterloo.flix.language.ast._
import ca.uwaterloo.flix.util.Validation._
import ca.uwaterloo.flix.util.vt.VirtualString._
import ca.uwaterloo.flix.util.vt.VirtualTerminal
import ca.uwaterloo.flix.util.{Timer, Validation}

/**
  * A phase that computes the effect of every expression in the program.
  */
object Effects extends Phase[Root, Root] {

  /**
    * An error raised to indicate that the expected effects of an expression does not match its actual effects.
    *
    * @param expected the expected effects.
    * @param inferred the inferred effects.
    * @param loc      the location where the error occurred.
    */
  case class EffectError(expected: Eff, inferred: Eff, loc: SourceLocation) extends CompilationError {
    val kind: String = "Effect Error"
    val source: SourceInput = loc.source
    val message: VirtualTerminal = {
      val vt = new VirtualTerminal()
      vt << Line(kind, source.format) << NewLine
      vt << ">> Inferred effect(s) do not match the expected effect(s)." << NewLine
      vt << NewLine
      vt << Code(loc, "unexpected effect(s).") << NewLine
      vt << NewLine
      vt << "Expected: " << Cyan(pretty(expected)) << NewLine
      vt << "Inferred: " << Magenta(pretty(inferred)) << NewLine
    }

    /**
      * Returns a human readable representation of the given effect `eff`.
      */
    private def pretty(eff: Eff): String = eff match {
      case Eff.Box(EffectSet.Bot) => "Bot"
      case Eff.Box(EffectSet.Top) => "Top"
      case Eff.Box(EffectSet.MayMust(may, must)) => s"may = {${may.mkString(", ")}}, must = {${must.mkString(", ")}}"
      case _ => eff.toString
    }
  }

  /**
    * Performs effect inference on the given AST `root`.
    */
  def run(root: Root)(implicit flix: Flix): Validation[Root, EffectError] = {
    val timer = new Timer({
      /**
        * Infer effects for definitions.
        */
      val definitionsVal = root.definitions.map {
        case (sym, defn0) => Declarations.infer(defn0, root) map {
          case defn => sym -> defn
        }
      }

      // TODO: Infer effects for constraints.

      for {
        definitions <- seqM(definitionsVal)
      } yield {
        root.copy(definitions = definitions.toMap)
      }
    })

    timer.getResult.map(root => root.copy(time = root.time.copy(effects = timer.getDuration)))
  }

  object Declarations {

    /**
      * Infers the effects of the given definition `defn0`.
      */
    def infer(defn0: Declaration.Definition, root: Root): Validation[Declaration.Definition, EffectError] = {
      // TODO: Introduce EffectParam

      val env0 = defn0.fparams.foldLeft(Map.empty[Symbol.VarSym, Eff]) {
        case (macc, TypedAst.FormalParam(sym, _, tpe, _)) => tpe match {
          case Type.Apply(Type.Arrow(_), _) =>
            macc + (sym -> Eff.Arrow(Eff.Pure, EffectSet.Bot, Eff.Pure, EffectSet.Bot))
          case _ => macc
        }
      }

      val expectedEff = defn0.eff

      Expressions.infer(defn0.exp, env0, root) flatMap {
        case e =>
          /*
           * Check that the declared effect matches the actual effect.
           */
          val actualEff = e.eff
          if (actualEff leq expectedEff)
            defn0.copy(exp = e).toSuccess
          else
            EffectError(expectedEff, actualEff, defn0.exp.loc).toFailure
      }

    }
  }

  object Expressions {

    /**
      * Infers the effects of the given expression `exp0`.
      */
    // TODO: naming of env
    def infer(exp0: Expression, env: Map[Symbol.VarSym, Eff], root: Root): Validation[Expression, EffectError] = {

      /**
        * Local visitor.
        */
      def visitExp(e0: Expression, env0: Map[Symbol.VarSym, Eff]): Validation[Expression, EffectError] = e0 match {
        /**
          * Literal Expression.
          */
        case Expression.Unit(loc) => e0.toSuccess
        case Expression.True(loc) => e0.toSuccess
        case Expression.False(loc) => e0.toSuccess
        case Expression.Char(lit, loc) => e0.toSuccess
        case Expression.Float32(lit, loc) => e0.toSuccess
        case Expression.Float64(lit, loc) => e0.toSuccess
        case Expression.Int8(lit, loc) => e0.toSuccess
        case Expression.Int16(lit, loc) => e0.toSuccess
        case Expression.Int32(lit, loc) => e0.toSuccess
        case Expression.Int64(lit, loc) => e0.toSuccess
        case Expression.BigInt(lit, loc) => e0.toSuccess
        case Expression.Str(lit, loc) => e0.toSuccess

        /**
          * Wildcard Expression.
          */
        case Expression.Wild(tpe, _, loc) =>
          // Wildcards are pure.
          Expression.Wild(tpe, Eff.Pure, loc).toSuccess

        /**
          * Variable Expression.
          */
        case Expression.Var(sym, tpe, _, loc) =>
          // Lookup the effect in the effect environment.
          val eff = env0.getOrElse(sym, Eff.Pure)
          Expression.Var(sym, tpe, eff, loc).toSuccess

        /**
          * Ref Expression.
          */
        case Expression.Ref(sym, tpe, _, loc) =>
          // The effect of a ref is its declared effect.
          val defn = root.definitions(sym)
          val latent = defn.eff.eff
          val eff = Eff.Arrow(Eff.Pure, latent, Eff.Pure, EffectSet.Bot)
          Expression.Ref(sym, tpe, eff, loc).toSuccess

        /**
          * Hook Expression.
          */
        case Expression.Hook(hook, tpe, _, loc) =>
          // TODO: Unsafely assume that hooks have no effects.
          val eff = Eff.Arrow(Eff.Pure, EffectSet.Bot, Eff.Pure, EffectSet.Bot)
          Expression.Hook(hook, tpe, eff, loc).toSuccess

        /**
          * Lambda Expression.
          */
        case Expression.Lambda(args, body, tpe, _, loc) =>
          for {
            e <- visitExp(body, env0)
          } yield {
            // TODO: deal with arguments
            val eff = Eff.Arrow(Eff.Pure, e.eff.eff, Eff.Pure, EffectSet.Bot)
            Expression.Lambda(args, body, tpe, eff, loc)
          }

        /**
          * Apply Expression.
          */
        case Expression.Apply(lambda, args, tpe, _, loc) =>
          for {
            e <- visitExp(lambda, env0)
            es <- seqM(args.map(e => visitExp(e, env0)))
          } yield {
            // TODO: This implementation is not yet fully correct!

            // Effects of lambda expression.
            val Eff.Arrow(_, latent, e2, eff) = e.eff

            // Effects of arguments.
            val argumentEffect = es.foldLeft(Eff.Pure) {
              case (eacc, exp) => eacc seq exp.eff
            }

            // The effects of the lambda expression happen before the effects the arguments.
            // Then the effects of applying the lambda happens.
            val resultEff = Eff.Box(latent) seq argumentEffect
            Expression.Apply(e, es, tpe, resultEff, loc)
          }

        /**
          * Unary Expression.
          */
        case Expression.Unary(op, exp, tpe, _, loc) =>
          for {
            e <- visitExp(exp, env0)
          } yield {
            val eff = e.eff
            Expression.Unary(op, e, tpe, eff, loc)
          }

        /**
          * Binary Expression.
          */
        case Expression.Binary(op, exp1, exp2, tpe, _, loc) =>
          for {
            e1 <- visitExp(exp1, env0)
            e2 <- visitExp(exp2, env0)
          } yield {
            // The effects of e1 happen before the effects of e2.
            val eff = e1.eff seq e2.eff
            Expression.Binary(op, exp1, exp2, tpe, eff, loc)
          }

        /**
          * Let Expression.
          */
        case Expression.Let(sym, exp1, exp2, tpe, _, loc) =>
          for {
            e1 <- visitExp(exp1, env0)
            e2 <- visitExp(exp2, env0 + (sym -> e1.eff.restrict))
          } yield {
            // The effects of e1 happen before e2.
            val eff = e1.eff seq e2.eff
            Expression.Let(sym, exp1, exp2, tpe, eff, loc)
          }

        /**
          * LetRec Expression.
          */
        case Expression.LetRec(sym, exp1, exp2, tpe, _, loc) =>
          for {
            e1 <- visitExp(exp1, env0)
            e2 <- visitExp(exp2, env0 + (sym -> e1.eff.restrict))
          } yield {
            // The effects of e1 happen before e2.
            val eff = e1.eff seq e2.eff
            Expression.LetRec(sym, exp1, exp2, tpe, eff, loc)
          }

        /**
          * If-Then-Else Expression.
          */
        case Expression.IfThenElse(exp1, exp2, exp3, tpe, _, loc) =>
          for {
            e1 <- visitExp(exp1, env0)
            e2 <- visitExp(exp2, env0)
            e3 <- visitExp(exp3, env0)
          } yield {
            // The effects of e1 happen before the effects of e2 and e3.
            val seq1 = e1.eff seq e2.eff
            val seq2 = e1.eff seq e3.eff

            // The effects of the overall expression is the least upper bound of the two effects above.
            val eff = seq1 lub seq2
            Expression.IfThenElse(e1, e2, e3, tpe, eff, loc)
          }

        /**
          * Match Expression.
          */
        case Expression.Match(exp, rules, tpe, _, loc) =>
          // Infer the effects of each rule.
          val rulesVal = rules.map {
            case MatchRule(pat, guard, body) =>
              for {
                g <- visitExp(guard, env0)
                b <- visitExp(body, env0)
              } yield {
                MatchRule(pat, g, b)
              }
          }

          // Infer the effects of the entire match expression.
          for {
            e <- visitExp(exp, env0)
            rs <- seqM(rulesVal)
          } yield {
            // Compute the effects of the match value expression.
            val matchEffect = e.eff

            // Compute the total effects of all the rules.
            val rulesEffect = rs.foldLeft(Eff.Bot) {
              case (eacc, MatchRule(pat, guard, body)) =>
                // The effect of the guard happens before the effect of the body.
                eacc lub (guard.eff seq body.eff)
            }

            // The effect of the match value expression happens before any effects of the rules.
            val eff = e.eff seq rulesEffect
            Expression.Match(e, rs, tpe, eff, loc)
          }

        /**
          * Switch Expression.
          */
        case Expression.Switch(rules, tpe, _, loc) =>
          // Infer the effects of each rule.
          val rulesVal = rules.map {
            case (guard, body) =>
              for {
                g <- visitExp(guard, env0)
                b <- visitExp(body, env0)
              } yield {
                (g, b)
              }
          }

          // Infer the effects.
          for {
            rs <- seqM(rulesVal)
          } yield {
            val eff = rs.foldLeft(Eff.Bot) {
              case (eacc, (guard, body)) =>
                eacc lub (guard.eff seq body.eff)
            }
            Expression.Switch(rs, tpe, eff, loc)
          }

        /**
          * Tag Expression.
          */
        case Expression.Tag(sym, tag, exp, tpe, _, loc) =>
          for {
            e <- visitExp(exp, env0)
          } yield {
            val eff = exp.eff
            Expression.Tag(sym, tag, e, tpe, eff, loc)
          }

        /**
          * Tuple Expression.
          */
        case Expression.Tuple(elms, tpe, _, loc) =>
          for {
            es <- seqM(elms.map(e => visitExp(e, env0)))
          } yield {
            // The effects of the each element expression happen in sequence.
            val eff = es.foldLeft(Eff.Bot) {
              case (eacc, e) => eacc seq e.eff
            }
            Expression.Tuple(elms, tpe, eff, loc)
          }

        /**
          * Existential Expression.
          */
        case Expression.Existential(fparam, exp, _, loc) =>
          // An existential expression must be pure.
          for {
            e <- visitExp(exp, env0)
            f <- assertPure(e)
          } yield {
            Expression.Existential(fparam, exp, f, loc)
          }

        /**
          * Universal Expression.
          */
        case Expression.Universal(fparam, exp, _, loc) =>
          // A universal expression must be pure.
          for {
            e <- visitExp(exp, env0)
            f <- assertPure(e)
          } yield {
            Expression.Universal(fparam, exp, f, loc)
          }

        /**
          * Ascribe Expression.
          */
        case Expression.Ascribe(exp, tpe, eff, loc) =>
          // An ascribe expression is sound; the effect system checks that the declared effect matches the inferred effect.
          for {
            e <- visitExp(exp, env0)
            _ <- assertLeq(exp, eff)
          } yield {
            Expression.Ascribe(e, tpe, eff, loc)
          }

        /**
          * Ascribe Expression.
          */
        case Expression.Cast(exp, tpe, eff, loc) =>
          // An cast expression is unsound; the effect system assumes the declared effect is correct.
          for {
            e <- visitExp(exp, env0)
          } yield {
            Expression.Cast(e, tpe, eff, loc)
          }

        /**
          * Native Constructor Expression.
          */
        case Expression.NativeConstructor(constructor, args, tpe, _, loc) =>
          // Unsoundly pretend that a native constructor is pure.
          val eff = Eff.Pure
          Expression.NativeConstructor(constructor, args, tpe, eff, loc).toSuccess

        /**
          * Native Field Expression.
          */
        case Expression.NativeField(field, tpe, _, loc) =>
          // Unsoundly pretend that a native field is pure.
          val eff = Eff.Pure
          Expression.NativeField(field, tpe, eff, loc).toSuccess

        /**
          * Native Method Expression.
          */
        case Expression.NativeMethod(method, args, tpe, _, loc) =>
          // Unsoundly pretend that a native method is pure.
          val eff = Eff.Pure
          Expression.NativeMethod(method, args, tpe, eff, loc).toSuccess

        /**
          * User Error Expression.
          */
        case Expression.UserError(tpe, _, loc) =>
          // Unsoundly pretend that a user error is pure.
          Expression.UserError(tpe, Eff.Pure, loc).toSuccess
      }

      visitExp(exp0, env)
    }
  }

  /**
    * Returns [[Success]] with the bottom effect if the given expression `e0` has the bottom effect.
    *
    * Otherwise returns [[Failure]] with an [[EffectError]].
    */
  private def assertPure(e0: Expression): Validation[Eff, EffectError] = assertLeq(e0, Eff.Pure)

  /**
    * Returns [[Success]] with the given effect `eff` if the effect of the
    * given expression `e0` is fully contained in the given effect `eff`.
    *
    * Otherwise returns [[Failure]] with an [[EffectError]].
    */
  private def assertLeq(e0: Expression, eff: Eff): Validation[Eff, EffectError] = {
    if (e0.eff leq eff)
      eff.toSuccess
    else
      EffectError(eff, e0.eff, e0.loc).toFailure
  }

}
