package io.circe

import cats.data.{ NonEmptyList, Validated, Xor }
import scala.annotation.tailrec
import scala.collection.generic.CanBuildFrom
import scala.collection.mutable.Builder

private[circe] final class MapDecoder[M[K, +V] <: Map[K, V], K, V](implicit
  dk: KeyDecoder[K],
  dv: Decoder[V],
  cbf: CanBuildFrom[Nothing, (K, V), M[K, V]]
) extends Decoder[M[K, V]] {
  def apply(c: HCursor): Decoder.Result[M[K, V]] = c.fields match {
    case None => Xor.left[DecodingFailure, M[K, V]](MapDecoder.failure(c))
    case Some(fields) =>
      val it = fields.iterator
      val builder = cbf.apply
      var failed: DecodingFailure = null

      while (failed.eq(null) && it.hasNext) {
        val field = it.next
        val atH = c.downField(field)

        atH.as(dv) match {
          case Xor.Left(error) =>
            failed = error
          case Xor.Right(value) => dk(field) match {
            case None =>
              failed = MapDecoder.failure(atH.any)
            case Some(k) =>
              builder += ((k, value))
          }
        }
      }

      if (failed.eq(null)) Xor.right(builder.result) else Xor.left(failed)
  }

  override def decodeAccumulating(c: HCursor): AccumulatingDecoder.Result[M[K, V]] =
    c.fields match {
      case None => Validated.invalidNel(MapDecoder.failure(c))
      case Some(fields) =>
        val builder = cbf()
        spinAccumulating(fields, c, builder, false, List.newBuilder[DecodingFailure]) match {
          case Nil => Validated.valid(builder.result())
          case error :: errors => Validated.invalid(NonEmptyList(error, errors))
        }
    }

  @tailrec
  private[this] def spinAccumulating(
    fields: List[String],
    c: HCursor,
    builder: Builder[(K, V), M[K, V]],
    failed: Boolean,
    errors: Builder[DecodingFailure, List[DecodingFailure]]
  ): List[DecodingFailure] = fields match {
    case Nil => errors.result
    case h :: t =>
      val atH = c.downField(h)

      (atH.as(dv), dk(h)) match {
        case (Xor.Left(error), _) => spinAccumulating(t, c, builder, true, errors += error)
        case (_, None) =>
          spinAccumulating(t, c, builder, true, errors += MapDecoder.failure(atH.any))
        case (Xor.Right(value), Some(k)) =>
          if (!failed) builder += (k -> value)

          spinAccumulating(t, c, builder, failed, errors)
      }
  }
}

private[circe] final object MapDecoder {
  def failure(c: HCursor): DecodingFailure = DecodingFailure("[K, V]Map[K, V]", c.history)
}