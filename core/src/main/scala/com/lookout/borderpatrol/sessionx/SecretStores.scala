package com.lookout.borderpatrol.sessionx

import org.jboss.netty.handler.codec.http.{DefaultHttpRequest, HttpRequest, HttpResponse, HttpVersion, HttpMethod}
import com.twitter.finagle.Service
import com.twitter.finagle.builder.ClientBuilder
import com.twitter.finagle.http._
import com.twitter.io.Charsets
import org.jboss.netty.buffer.ChannelBuffers


/**
 * Two secrets must be in rotation at any given time:
 *  - Current: used for creating new sessions and validating incoming non-expired sessions
 *  - Previous: validating incoming non-expired sessions, e.g. sessions signed by yesterday's key
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

  class ConsulSecretStore(consul: Service[HttpRequest,HttpResponse]) extends SecretStoreApi {
    
    
    
    def current: Secret = {
      val req = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/v1/kv/?recurse")
      val f = consul(req) // Client, send the request

    // Handle the response:
      f onSuccess { res =>
       println("got response" + res.getContent.toString(Charsets.Utf8) )
      } onFailure { exc =>
        println("failed :-(" + exc)
      }
      Secret()
    }

    def previous: Secret = {
      Secret()
    }

    def find(f: Secret => Boolean): Option[Secret] = {
      None
    }

    def update(newSecret: Secret): Boolean ={
       val data = ChannelBuffers.copiedBuffer("String from borderpatrol", Charsets.Utf8)
      val req: HttpRequest= RequestBuilder()
        .url("http://localhost:8500/v1/kv/web/key2")
        .buildPut(data)
      //val req = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/v1/kv/secretStore/current")
    
     val f = consul(req)
      f onSuccess { res =>
       println("got response" + res.getContent.toString(Charsets.Utf8) )
      } onFailure { exc =>
        println("failed :-(" + exc)
      }
      true
    }
  }

  object ConsulSecretStore{

    def apply(consulUrl: String, consulPort: String):ConsulSecretStore = {
      var apiUrl = s"$consulUrl:$consulPort"
      println(apiUrl)
      val client: Service[HttpRequest, HttpResponse] = ClientBuilder()
        .codec(Http())
        .hosts(apiUrl) 
        .hostConnectionLimit(5)
        .build()

      val c = new ConsulSecretStore(client)
      c
    }
    
  }

}
