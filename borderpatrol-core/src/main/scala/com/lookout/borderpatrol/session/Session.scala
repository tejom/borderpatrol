package com.lookout.borderpatrol.session

import java.util.concurrent.TimeUnit

import com.lookout.borderpatrol.RoutedRequest
import com.twitter.finagle.http.{Request => FinagleRequest}
import com.twitter.util.{Future, Duration}
import org.jboss.netty.handler.codec.http.HttpRequest

import scala.util.{Success, Try}


object SessionExpiry extends Expiry {
  val lifetime = Duration(1, TimeUnit.DAYS)
}

sealed trait Session {
  val id: SessionId
  val originalRequest: HttpRequest
  val tokens: Tokens
}

object Session {
  import SecureSession.{generator,secretStore,sessionStore}

  def apply(id: SessionId, originalRequest: HttpRequest, tokens: Tokens): Session =
    sessionStore.update(ExistingSession(id, originalRequest, tokens))

  def apply(s: String, originalRequest: HttpRequest): Session =
    sessionStore.get(s) getOrElse NewSession(originalRequest)

  def apply(request: RoutedRequest): Session = {
    println("Setting Session...")
    request.borderCookie.flatMap(id => sessionStore.get(id)) getOrElse NewSession(request.httpRequest)
  }
}

case class NewSession(originalRequest: HttpRequest)(implicit g: SessionIdGenerator, s: SecretStoreApi) extends Session {
  lazy val id = g.next
  val tokens = Tokens(EmptyToken, EmptyServiceTokens)
}

case class ExistingSession(id: SessionId, originalRequest: HttpRequest, tokens: Tokens) extends Session

/**
 * This prototypes an API, and should be implemented using some shared store.
 *
 * No coordination is needed for the store, but, should be implemented using an HA
 * store.
 */
sealed trait SessionStoreApi {
  def get(s: String): Option[Session]
  def update(s: Session): Session
}

trait SessionStoreComponent {
  implicit val marshaller: SessionIdMarshaller
  val sessionStore: SessionStoreApi
}

case class InMemorySessionStore(implicit marshaller: SessionIdMarshaller) extends SessionStoreApi {
  private [this] var _store = Map[String, Session]()

  def get(id: String): Option[Session] =
    (_store get id ) filterNot (_.id.expired)

  def get(id: SessionId): Option[Session] =
    get(id.asString)

  def update(s: Session): Session = {
    _store = _store.updated(s.id.asString, s)
    s
  }
}