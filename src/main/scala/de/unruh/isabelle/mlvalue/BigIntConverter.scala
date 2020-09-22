package de.unruh.isabelle.mlvalue
import java.math.BigInteger

import de.unruh.isabelle.control.Isabelle
import de.unruh.isabelle.control.Isabelle.{DInt, DList, DString}
import de.unruh.isabelle.mlvalue.MLValue.Ops

import scala.concurrent.{ExecutionContext, Future}

/**
 * [[MLValue.Converter]] for [[scala.BigInt BigInt]]s.
 *
 *  - ML type: `int`
 *  - Encoding of an integer `i` as an exception: `E_Int i`
 *
 * Note that [[IntConverter]], [[LongConverter]], [[BigIntConverter]] are different [[MLValue.Converter Converter]]s for the same ML type `int`.
 * They have compatible representations as exceptions, they can safely be typecast into each other.
 *
 * @see MLValue.Converter for explanations what [[MLValue.Converter Converter]]s are for.
 */

object BigIntConverter extends MLValue.Converter[BigInt] {
  override def mlType: String = IntConverter.mlType

  override def retrieve(value: MLValue[BigInt])(implicit isabelle: Isabelle, ec: ExecutionContext): Future[BigInt] =
    for (DString(str) <- Ops.retrieveBigInt(value))
      yield BigInt(str.replace('~','-'))

  override def store(value: BigInt)(implicit isabelle: Isabelle, ec: ExecutionContext): MLValue[BigInt] =
    Ops.storeBigInt(DString(value.toString))

  override def exnToValue: String = IntConverter.exnToValue

  override def valueToExn: String = IntConverter.valueToExn
}
