package zhttp.http

import zio.Chunk
import zio.stream.ZStream

/**
 * Extracts data from `Content`
 */
sealed trait HasData[-A] {
  type Out[-R, +E]
  def data[R, E, A1 <: A](content: HttpData[R, E, A1]): Out[R, E]
}

object HasData {
  import HttpData._
  implicit case object Complete extends HasData[Complete] {
    override type Out[-R, +E] = Chunk[Byte]
    override def data[R, E, A1 <: Complete](content: HttpData[R, E, A1]): Out[R, E] = content match {
      case CompleteContent(bytes) => bytes
      case _                      => throw new Error("Data is Unavailable")
    }
  }
  implicit case object Buffered extends HasData[Buffered] {
    override type Out[-R, +E] = ZStream[R, E, Byte]
    override def data[R, E, A1 <: Buffered](content: HttpData[R, E, A1]): Out[R, E] = content match {
      case BufferedContent(source) => source
      case _                       => throw new Error("Data is Unavailable")
    }
  }
}