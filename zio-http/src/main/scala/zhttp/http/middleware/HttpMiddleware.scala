package zhttp.http.middleware

import zhttp.http._
import zhttp.http.middleware.HttpMiddleware.RequestP
import zio.{UIO, ZIO}

/**
 * Middlewares for HttpApp.
 */
sealed trait HttpMiddleware[-R, +E] { self =>
  def apply[R1 <: R, E1 >: E](app: HttpApp[R1, E1]): HttpApp[R1, E1] = HttpMiddleware.transform(self, app)

  def ++[R1 <: R, E1 >: E](other: HttpMiddleware[R1, E1]): HttpMiddleware[R1, E1] =
    self combine other

  def combine[R1 <: R, E1 >: E](other: HttpMiddleware[R1, E1]): HttpMiddleware[R1, E1] =
    HttpMiddleware.Combine(self, other)

  def race[R1 <: R, E1 >: E](app: HttpMiddleware[R1, E1]): HttpMiddleware[R1, E1] =
    HttpMiddleware.Race(app, self)

  def replace[R1 <: R, E1 >: E](app: HttpApp[R1, E1]): HttpMiddleware[R1, E1] =
    HttpMiddleware.Constant(app)

  def replaceWhen[R1 <: R, E1 >: E](f: HttpMiddleware.RequestP[Boolean], app: HttpApp[R1, E1]): HttpMiddleware[R1, E1] =
    HttpMiddleware.fromMiddlewareFunction { (method, url, headers) =>
      if (f(method, url, headers)) self else HttpMiddleware.fromApp(app)
    }

  def when(f: RequestP[Boolean]): HttpMiddleware[R, E] =
    HttpMiddleware.fromMiddlewareFunction { (m, u, h) =>
      if (f(m, u, h)) self else HttpMiddleware.empty
    }
}

object HttpMiddleware {

  type RequestP[+A] = (Method, URL, List[Header]) => A

  private final case object Empty extends HttpMiddleware[Any, Nothing]

  private final case class TransformM[R, E, S](
    req: (Method, URL, List[Header]) => ZIO[R, Option[E], S],
    res: (Status, List[Header], S) => ZIO[R, Option[E], Patch],
  ) extends HttpMiddleware[R, E]

  private final case class Combine[R, E](self: HttpMiddleware[R, E], other: HttpMiddleware[R, E])
      extends HttpMiddleware[R, E]

  private final case class FromFunctionM[R, E](
    f: (Method, URL, List[Header]) => ZIO[R, Option[E], HttpMiddleware[R, E]],
  ) extends HttpMiddleware[R, E]

  final case class Race[R, E](effect: HttpMiddleware[R, E], middleware: HttpMiddleware[R, E])
      extends HttpMiddleware[R, E]

  final case class Constant[R, E](app: HttpApp[R, E]) extends HttpMiddleware[R, E]

  final case class PartiallyAppliedMake[S](req: (Method, URL, List[Header]) => S) extends AnyVal {
    def apply(res: (Status, List[Header], S) => Patch): HttpMiddleware[Any, Nothing] =
      TransformM[Any, Nothing, S](
        (method, url, headers) => UIO(req(method, url, headers)),
        (status, headers, state) => UIO(res(status, headers, state)),
      )
  }

  final case class PartiallyAppliedMakeM[R, E, S](req: (Method, URL, List[Header]) => ZIO[R, Option[E], S])
      extends AnyVal {
    def apply[R1 <: R, E1 >: E](res: (Status, List[Header], S) => ZIO[R1, Option[E1], Patch]): HttpMiddleware[R1, E1] =
      TransformM(req, res)
  }

  /**
   * An empty middleware that doesn't do anything
   */
  def empty: HttpMiddleware[Any, Nothing] = Empty

  /**
   * Creates a new middleware using transformation functions
   */
  def make[S](req: (Method, URL, List[Header]) => S): PartiallyAppliedMake[S] = PartiallyAppliedMake(req)

  /**
   * Creates a new middleware using effectful transformation functions
   */
  def makeM[R, E, S](req: (Method, URL, List[Header]) => ZIO[R, Option[E], S]): PartiallyAppliedMakeM[R, E, S] =
    PartiallyAppliedMakeM(req)

  /**
   * Creates a new constants middleware that always executes the app provided, independent of where the middleware is
   * applied
   */
  def fromApp[R, E](app: HttpApp[R, E]): HttpMiddleware[R, E] = HttpMiddleware.Constant(app)

  /**
   * Creates a new middleware using a function from request parameters to a ZIO of HttpMiddleware
   */
  def fromMiddlewareFunctionM[R, E](f: RequestP[ZIO[R, Option[E], HttpMiddleware[R, E]]]): FromFunctionM[R, E] =
    HttpMiddleware.FromFunctionM(f)

  /**
   * Creates a new middleware using a function from request parameters to a HttpMiddleware
   */
  def fromMiddlewareFunction[R, E](f: RequestP[HttpMiddleware[R, E]]): FromFunctionM[R, E] =
    fromMiddlewareFunctionM((method, url, headers) => UIO(f(method, url, headers)))

  private[zhttp] def transform[R, E](mid: HttpMiddleware[R, E], app: HttpApp[R, E]): HttpApp[R, E] =
    mid match {
      case Empty => app

      case TransformM(reqF, resF) =>
        HttpApp.fromPartialFunction { req =>
          for {
            s     <- reqF(req.method, req.url, req.headers)
            res   <- app(req)
            patch <- resF(res.status, res.headers, s)
          } yield patch(res)
        }

      case Combine(self, other) => other(self(app))

      case FromFunctionM(f) =>
        HttpApp.fromPartialFunction { req =>
          for {
            output <- f(req.method, req.url, req.headers)
            res    <- output(app)(req)
          } yield res
        }

      case Race(effect, self) =>
        HttpApp.fromPartialFunction { req =>
          for {
            output <- effect
            res    <- output(req).raceFirst(self(app)(req))
          } yield res
        }

      case Constant(self) => self
    }

}
