package com.lookout.borderpatrol.sessionx

import com.twitter.io.Buf
import argonaut._, Argonaut._
import com.twitter.util._
import com.twitter.finagle.{Httpx, Service}
import com.twitter.finagle.httpx
import scala.util.{Success, Failure, Try}


/**
 * Two secrets must be in rotation at any given time:
 * - Current: used for creating new sessions and validating incoming non-expired sessions
 * - Previous: validating incoming non-expired sessions, e.g. sessions signed by yesterday's key
 *
 * Since each [[com.lookout.borderpatrol.sessionx.Secret Secret]] expires (default of 1 day), the
 * window of a non-expired [[com.lookout.borderpatrol.sessionx.Session Session]] is somewhere between the expiry of
 * the current `Secret` and the previous `Secret`
 */
trait SecretStoreApi {
  def current: Secret

  def previous: Secret

  def find(f: Secret => Boolean): Option[Secret]
}

/**
 * Default implementations of [[com.lookout.borderpatrol.sessionx.SecretStoreApi SecretStoreApi]]
 */
object SecretStores {
  val ConsulSecretsKey = "secretStore/secrets"

  /**
   * A useful [[com.lookout.borderpatrol.sessionx.Secrets Secrets]] mock store for quickly testing and prototyping
   *
   * @param secrets the current secret and previous secret
   */
  case class InMemorySecretStore(secrets: Secrets) extends SecretStoreApi {
    @SuppressWarnings(Array("org.brianmckenna.wartremover.warts.Var")) // this is for mocking
    private[this] var _secrets: Secrets = secrets

    def current: Secret = {
      val c = _secrets.current
      if (c.expired) {
        _secrets = _secrets.copy(Secret(), c)
        _secrets.current
      }
      else c
    }

    def previous: Secret =
      _secrets.previous

    def find(f: (Secret) => Boolean): Option[Secret] =
      if (f(current)) Some(current)
      else if (f(previous)) Some(previous)
      else None

  }

  /**
   * A store to access the current and previous [[com.lookout.borderpatrol.sessionx.Secret]] stored in the consul
   * server.
   * It will use a in memory cache before making a request to the server
   *
   * @param consul An instance of [[com.lookout.borderpatrol.sessionx.SecretStores.ConsulConnection]] to make requests
   *               to the consul server
   * @param poll How often in seconds to check for updated Secrets on the consul server
   */
  case class ConsulSecretStore(consul: ConsulClient, poll: Long) extends SecretStoreApi {
    val cache = ConsulSecretCache(poll, consul)
    new Thread(cache).start()

    /**
     * Get the current secret from the cache layer
     */
    def current: Secret =
      cache.secrets.current

    /**
     * Get the previous secret from the cache layer
     */
    def previous: Secret =
      cache.secrets.previous

    /**
     * Look for the Secret being checked in the function. Checks if this exists in the cache layer
     * If it is found only existing in consul and not in current or previous a cache rotation will be triggered
     * Returning None suggests the servers are extremely out of sync with each other or the connection to consul has
     * failed
     */
    def find(f: Secret => Boolean): Option[Secret] =
      cache.find(f)

  }

  /**
   * Stores the secrets stored on the consul server.
   * Polls the consul server and keeps an inmemory cache of Secrets
   *
   * @param poll How often in seconds to check for updates
   * @param consul An instance on ConsulConnection to make connections with the consul server
   */
  case class ConsulSecretCache(poll: Long, consul: ConsulClient) extends Runnable {
    val cacheBuffer = collection.mutable.ArrayBuffer[Secrets]( Secrets(Secret(Time.fromMilliseconds(0)), Secret()))
    val newBuffer = collection.mutable.ArrayBuffer[Secrets]()

    /**
     * Checks if the current secret is expired and if there is a new secret available and rotates the secrets.
     * Then returns the latest secret
     */
    def secrets: Secrets = {
      val s = cacheBuffer.last
      if (s.current.expired) rotateSecret
      else s
    }

    /**
     * Checks if the secret is knows. Rotates if the secret is the new secret.
     */
    def find(f: Secret => Boolean): Option[Secret] = {
      val lastSecrets = cacheBuffer.last

      if (f(lastSecrets.current)) Some(lastSecrets.current)
      else if (f(lastSecrets.previous)) Some(lastSecrets.previous)
      else if (f(rotateSecret.current)) Some(cacheBuffer.last.current)
      else None
    }

    /**
     * Continuously poll the consul server at the interval passed to the Class when it was created
     * updates the store for a possibly new Secret to be used
     */
    def run(): Unit =
      while (true) {
        for {
          n <- pollSecrets
        } yield n match {
          case Some(s) => newBuffer.append(s)
          case None => ()
        }
        Thread.sleep(poll)
      }

    private def needsRotation: Boolean =
      newBuffer.nonEmpty && newBuffer.last.current != cacheBuffer.last.current

    private def rotateSecret: Secrets = {
      if (needsRotation) {
        this.synchronized {
          if (needsRotation)
            cacheBuffer.append(newBuffer.last)
        }
      }
      cacheBuffer.last
    }

    /**
     * Get the secret at current from the consul server or returns None
     */
    private def pollSecrets: Future[Option[Secrets]] =
      consul.value(ConsulSecretsKey).map({
        case Success(a) => secretsTryFromString(a).toOption
        case Failure(e) => None
      })

    /**
     * Returns a Try[Secret] from json as a [String]
     *
     * @param s A json string with information to create a Secret
     */
    private def secretsTryFromString(s: String): Try[Secrets] = {
      import scalaz._
      Parse.parse(s) match {
        case -\/(e) => util.Failure(new Exception(s"Failed with: $e"))
        case \/-(j) => SecretsEncoder.EncodeJson.decode(j)
      }
    }
  }

  /**
   *Define functions for a ConsulClient connection. Used by the the ConsulConnection and MockConsulClient
   */
  trait ConsulClient {
    def value(k: String): Future[Try[String]]

    def set(k: String, v: String): Future[httpx.Response]
  }

  /**
   * class to interface with consul kv store
   *
   * @param consul Finagle server to send and get requests to the server
   * @param host host name of the consul server to connect to ex: "localhost"
   */
  class ConsulConnection(consul: Service[httpx.Request, httpx.Response], host: String, port: String)
    extends ConsulClient {

    /**
     * Return a json String from a consul key URL
     *
     * @param k key to get a consul json response from
     */
    private def consulResponse(k: String): Future[Buf] = {
      val req = httpx.Request(httpx.Method.Get, k)
      req.host = host
      consul(req).map(a => a.content)
    }

    private def base64ToString(s: String): Option[String] = {
      val buf = com.twitter.util.Base64StringEncoder.decode(s)
      Buf.Utf8.unapply(Buf.ByteArray.Owned(buf))
    }

    /**
     * Get just the decoded value for a key from consul as Future[String]. To get the full json response from Consul
     * use getConsulRepsonse
     *
     * @param key the key to get the value for
     */
    def value(key: String): Future[Try[String]] =
      consulResponse("/v1/kv/" + key) map { buf =>
        (for {
          body <- Buf.Utf8.unapply(buf)
          rlist <- body.decodeOption[List[ConsulResponse]]
          res <- rlist.headOption
          value <- base64ToString(res.value)
        } yield value) match {
          case None => Failure(new Exception("Unable to decode consul response"))
          case Some(f) => Success(f)
        }
      }

    /**
     * Set the given key to the given Value. Both are strings
     *
     * @param k the key to set
     * @param v the value of the key
     */
    def set(k: String, v: String): Future[httpx.Response] = {
      val currentData = Buf.Utf8(v)
      val update: httpx.Request = httpx.RequestBuilder()
        .url(s"http://$host:$port/v1/kv/$k")
        .buildPut(currentData)
      consul(update)
    }
  }

  object ConsulSecretStore {
    /**
     * Create a ConsulSecretStore to use.
     *
     * @param consulUrl the host name of the server
     * @param consulPort the port the kv store is listening on. Consul default is 8500
     * @param poll How often to check for updates on the consul server
     */
    def apply(consulUrl: String, consulPort: String, poll: Int): ConsulSecretStore = {
      val apiUrl = s"$consulUrl:$consulPort"
      val client: Service[httpx.Request, httpx.Response] = Httpx.newService(apiUrl)
      val consulConnection = new ConsulConnection(client, consulUrl, consulPort)
      new ConsulSecretStore(consulConnection, poll)
    }
  }

  /**
   * Class to use for Argonaut json conversion
   */
  case class ConsulResponse(
                             flags: Int, modifyIndex: Int, value: String, lockIndex: Int, createIndex: Int, key: String)

  object ConsulResponse {
    implicit val ConsulResponseCodec: CodecJson[ConsulResponse] =
      casecodec6(ConsulResponse.apply, ConsulResponse.unapply)(
        "Flags", "ModifyIndex", "Value", "LockIndex", "CreateIndex", "Key")
  }

}
