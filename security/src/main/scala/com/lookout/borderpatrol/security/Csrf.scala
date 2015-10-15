package com.lookout.borderpatrol.security

import com.lookout.borderpatrol.util.Combinators.tap
import com.lookout.borderpatrol.sessionx._
import com.twitter.finagle.{Service, Filter}
import com.twitter.finagle.httpx.{Request, Response}
import com.twitter.util.Future

object Csrf {
  case class InHeader(val header: String = "X-BORDER-CSRF") extends AnyVal
  case class Param(val param: String = "_x_border_csrf") extends AnyVal
  case class CookieName(val name: String = "border_csrf") extends AnyVal
  case class VerifiedHeader(val header: String = "X-BORDER-CSRF-VERIFIED") extends AnyVal

  /**
   * Informs upstream service about Csrf validation via double submit cookie
   *
   * @param header The incoming header that contains the CSRF token
   * @param param The incoming parameter that contains the CSRF token
   * @param cookieName The cookie that contains the CSRF token
   * @param verifiedHeader The verified header to set
   */
  case class Verify(header: InHeader,
                    param: Param,
                    cookieName: CookieName,
                    verifiedHeader: VerifiedHeader)(implicit secretStoreApi: SecretStoreApi) {

    /**
     * Inject the value of the call to verify in the VerifiedHeader
     * It's unsafe, because it mutates the Request
     */
    def unsafeInject(req: Request)(f: Boolean => String): Request =
      tap(req)(_.headerMap.set(verifiedHeader.header, f(verify(req))))

    /**
     * Check that CSRF header/param is there, validates that the cookie and header/param are valid SessionIds
     * If the header is not present it will look for the parameter.
     * @return false unless all checks are valid
     */
    def verify(req: Request): Boolean =
      (for {
        str <- req.headerMap.get(header.header) orElse req.params.get(param.param)
        ckv <- req.cookies.getValue(cookieName.name)
        uid <- SessionId.from(str).toOption
        cid <- SessionId.from(ckv).toOption
      } yield uid == cid) getOrElse false
  }
}

/**
 * Sets the CSRF header for inspection by upstream service. Always sets csrf verified header to false unless
 * the csrf cookie and the header/param match and are valid.
 */
case class CsrfFilter(verify: Csrf.Verify) extends Filter[Request, Response, Request, Response] {

  def apply(req: Request, service: Service[Request, Response]): Future[Response] =
    service(verify.unsafeInject(req)(_.toString))
}
