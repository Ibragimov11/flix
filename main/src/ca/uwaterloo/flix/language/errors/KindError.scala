/*
 * Copyright 2021 Matthew Lutze
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
package ca.uwaterloo.flix.language.errors

import ca.uwaterloo.flix.api.Flix
import ca.uwaterloo.flix.language.CompilationMessage
import ca.uwaterloo.flix.language.ast.{Kind, RigidityEnv, SourceLocation, Symbol, Type}
import ca.uwaterloo.flix.language.fmt.FormatKind.formatKind
import ca.uwaterloo.flix.language.fmt.FormatType.formatType
import ca.uwaterloo.flix.util.Formatter

/**
  * A common super-type for kind errors.
  */
sealed trait KindError extends CompilationMessage {
  val kind: String = "Kind Error"
}

object KindError {
  /**
    * An error describing mismatched inferred kinds.
    *
    * @param k1  the first kind.
    * @param k2  the second kind.
    * @param loc the location where the error occurred.
    */
  case class MismatchedKinds(k1: Kind, k2: Kind, loc: SourceLocation) extends KindError {
    override def summary: String = s"Mismatched kinds: '${formatKind(k1)}' and '${formatKind(k2)}''"

    def message(formatter: Formatter): String = {
      import formatter._
      s"""${line(kind, source.name)}
         |>> This type variable was used as both kind '${red(formatKind(k1))}' and kind '${red(formatKind(k2))}'.
         |
         |${code(loc, "mismatched kind.")}
         |
         |Kind One: ${cyan(formatKind(k1))}
         |Kind Two: ${magenta(formatKind(k2))}
         |""".stripMargin
    }

    /**
      * Returns a formatted string with helpful suggestions.
      */
    def explain(formatter: Formatter): Option[String] = None
  }

  /**
    * An error describing a kind that doesn't match the expected kind.
    *
    * @param expectedKind the expected kind.
    * @param actualKind   the actual kind.
    * @param loc          the location where the error occurred.
    */
  case class UnexpectedKind(expectedKind: Kind, actualKind: Kind, loc: SourceLocation) extends KindError {
    override def summary: String = s"Kind ${formatKind(expectedKind)} was expected, but found ${formatKind(actualKind)}."

    def message(formatter: Formatter): String = {
      import formatter._
      s"""${line(kind, source.name)}
         |>> Expected kind '${red(formatKind(expectedKind))}' here, but kind '${red(formatKind(actualKind))}' is used.
         |
         |${code(loc, "unexpected kind.")}
         |
         |Expected kind: ${cyan(formatKind(expectedKind))}
         |Actual kind:   ${magenta(formatKind(actualKind))}
         |""".stripMargin
    }

    /**
      * Returns a formatted string with helpful suggestions.
      */
    def explain(formatter: Formatter): Option[String] = None
  }

  /**
    * An error resulting from a type whose kind cannot be inferred.
    *
    * @param loc The location where the error occurred.
    */
  case class UninferrableKind(loc: SourceLocation) extends KindError {
    override def summary: String = "Unable to infer kind."

    def message(formatter: Formatter): String = {
      import formatter._
      s"""${line(kind, source.name)}
         |>> Unable to infer kind.
         |
         |${code(loc, "uninferred kind.")}
         |
         |""".stripMargin
    }

    def explain(formatter: Formatter): Option[String] = Some({
      import formatter._
      s"${underline("Tip: ")} Add a kind annotation."
    })
  }


  /**
    * Missing type class constraint.
    *
    * @param clazz the class of the constraint.
    * @param tpe   the type of the constraint.
    * @param renv  the rigidity environment.
    * @param loc   the location where the error occurred.
    */
  case class MissingConstraint(clazz: Symbol.ClassSym, tpe: Type, renv: RigidityEnv, loc: SourceLocation)(implicit flix: Flix) extends KindError {
    def summary: String = s"No constraint of the '$clazz' class for the type '${formatType(tpe, Some(renv))}'"

    def message(formatter: Formatter): String = {
      import formatter._
      s"""${line(kind, source.name)}
         |>> No constraint of the '${cyan(clazz.toString)}' class for the type '${red(formatType(tpe, Some(renv)))}'.
         |
         |${code(loc, s"missing constraint")}
         |
         |""".stripMargin
    }

    def explain(formatter: Formatter): Option[String] = None
  }
}
