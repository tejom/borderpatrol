package com.lookout.borderpatrol

import com.twitter.finagle.httpx.Request
import com.twitter.finagle.httpx.path.Path

/*
 * We derive a service `name` (a [[String]] name referencing a [[com.twitter.finagle.Name Name]]) either via the `Path`
 * or given a path of `/` we use the subdomain of the host.
 *
 * There are two ways to determine the intending service to route to:
 *  1) `http://example.com/:service/rest/of/path` => extract `:service`, then lookup a matching `ServiceIdentifier`
 *  2) `http://service.example.com/` => if path of `/`, extract subdomain `service`, lookup a matching
 *  `ServiceIdentifier`
 *
 * `Path` based matching must _always_ override the fallback `subdomain` matching.
 *
 * @example
 *          Let's say we have a service named "enterprise", if we
 *          define the instance of that as:
 *            {{{
 *            val ent = Service("enterprise", Path("/ent"), "default")
 *            val biz = Service("business", Path("/biz"), "api")
 *            }}}
 *
 *          The following urls would match the services:
 *            - api.example.com/ent => ent
 *            - default.example.com/ => ent
 *            - default.example.com/biz => biz
 *            - api.example.com => biz
 *            - api.example.com/ent => ent
 *          These would not match the services:
 *            - a.api.example.com => ???
 *            - example.com => ???
 */
case class ServiceMatcher(services: Set[ServiceIdentifier]) {

  val domainTerm = "."
  val pathTerm = "/"

  /**
   * Helper for finding longest subdomain prefix in a set
   * @param sis A set of identifiers that have matched some precondition
   * @param cmp A comparable function
   * @return The maximuma of folding over the set with the cmp function
   */
  private[this] def foldWith(sis: Set[ServiceIdentifier],
                             cmp: (ServiceIdentifier, ServiceIdentifier) => ServiceIdentifier):
      Option[ServiceIdentifier] =
    sis.foldRight(Option.empty[ServiceIdentifier])((lhs, res) => res match {
      case Some(rhs) => Some(cmp(lhs, rhs))
      case None => Some(lhs)
    })

  /**
   * Gives the name of the service that best matches the subdomain of the host string, or None
   *
   * @example
     *          Given a host of "sub.subdomain.example.org" and a Set[ServiceIdentifier] of
   *          {{{
   *            Set(ServiceIdentifier("one", "/s", "sub.subdomain"),
   *                ServiceIdentifier("two", "/s2", "sub"))
   *          }}}
   *          return the [[ServiceIdentifier.name]] "one" because it is the longest matching
   * @param host The fully qualified host name
   * @return the service name from the longest matching subdomain
   */
  def subdomain(host: String): Option[ServiceIdentifier] =
    foldWith(
      services.filter(si => host.startsWith(si.subdomain + domainTerm)),
      (si1, si2) => if (si1.subdomain.size > si2.subdomain.size) si1 else si2
    )

  /**
   * Find the longest matching path in the request
   *
   * @example
   *          Given a request of path of "/a" and a set of paths Set("/account", "/a")
   * @param pathString path string from request
   * @return the service name from the longest matching path
   */
  def path(pathString: String): Option[ServiceIdentifier] = {
    path(Path(pathString))
  }

  /**
   * Find the longest matching path in the request
   *
   * @example
   *          Given a request of path of "/a" and a set of paths Set("/account", "/a")
   * @param path path from request
   * @return the service name from the longest matching path
   */
  def path(path: Path): Option[ServiceIdentifier] =
    foldWith(
      services.filter(id => path.startsWith(id.path)),
      (si1, si2) => if (si1.path.toString.size > si2.path.toString.size) si1 else si2
    )

  /**
   * Derive a ServiceIdentifier from an `httpx.Request`
   */
  def get(req: Request): Option[ServiceIdentifier] =
    path(req.path) orElse req.host.flatMap(subdomain)

}

