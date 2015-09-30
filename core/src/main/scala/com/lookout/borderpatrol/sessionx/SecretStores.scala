package com.lookout.borderpatrol.sessionx

import org.jboss.netty.handler.codec.http.{DefaultHttpRequest, HttpRequest, HttpResponse, HttpVersion, HttpMethod}
import com.twitter.finagle.Service
import com.twitter.finagle.builder.ClientBuilder
import com.twitter.io.{Charsets, Buf}
import org.jboss.netty.buffer.ChannelBuffers
import java.util.Base64.Decoder._
import java.nio.charset._
import argonaut._, Argonaut._
import com.twitter.util.Future
import com.twitter.finagle.{Httpx, Service}
import com.twitter.finagle.httpx
import scala.util.{Success, Failure, Try}



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

  class ConsulSecretStore(consul: Service[httpx.Request, httpx.Response],host: String) {
    
    
    def current: Option[Secret] = {
      val r = getValue("/v1/kv/secretStore/current")
      val tryResponse = secretTryFromFutureString(r)
      tryResponse.toOption
    }

     def previous: Option[Secret] = {
      val r = getValue("/v1/kv/secretStore/previous") 
      val tryResponse = secretTryFromFutureString(r)
      tryResponse.toOption
    }

    def find(f: Secret => Boolean): Option[Secret] = {
      /*
      TODO
      */
      None
    }

    def update(newSecret: Secret): Future[String] ={
      val secretHolder = previous.getOrElse( Secret() )
      val newEncodedSecret = SecretEncoder.EncodeJson.encode(newSecret)
      println(newEncodedSecret)

      
      val data = Buf.Utf8(newEncodedSecret.nospaces)
      val req :httpx.Request = httpx.RequestBuilder()
         .url("http://localhost:8500/v1/kv/secretStore/current")
         .buildPut(data)
      
    
       val f = consul(req)
      f.map(a => a.getContentString)
    }
    /**
    *Returns a Try[Secret] from json a Futrue[String]
    **/
    private def secretTryFromFutureString(s: Future[String]): Try[Secret] = {
      
      val json: Future[Option[Json]] = s.map( a=> Parse.parseOption(a))
      val tryResponse = json.map(a => SecretEncoder.EncodeJson.decode(a.get)).get
      tryResponse

    }
    /**
    *Decode consul base64 response to a readable String
    **/
    private def base64Decode(s: String): String ={
      val decodedArray = java.util.Base64.getDecoder().decode(s);
      new String(decodedArray, StandardCharsets.UTF_8)
    }
    /**
    *Return a json String from a consul key URL
    **/
    private def getConsulResponse(k: String): Future[String] = {
      val req = httpx.Request(httpx.Method.Get, k)
      req.host = host
      consul(req).map(a => a.getContentString)
    }
    /**
    *Get just the value for a key from consul as Future[String]. To get the full json response from Consul
    *use getConsulRepsonse
    **/
    private def getValue(k: String): Future[String] = {
      val s = getConsulResponse(k)
      val decodedJSONList = s.map(a => a.decodeOption[List[ConsulSecretStore.ConsulResponse]].getOrElse(Nil))
      decodedJSONList.map(a=> base64Decode(a.headOption.get.Value))
    }

    
  }

  object ConsulSecretStore{

    def apply(consulUrl: String, consulPort: String):ConsulSecretStore = {
      val apiUrl = s"$consulUrl:$consulPort"
      println(apiUrl)
      val client: Service[httpx.Request, httpx.Response] = Httpx.newService(apiUrl)

      val c = new ConsulSecretStore(client,consulUrl)
      c
    }
    
    case class ConsulResponse(CreateIndex: Int, ModifyIndex: Int,LockIndex: Int,Key: String, Flags: Int, Value: String)
    object ConsulResponse {
      implicit def ConsulResponseCodec: CodecJson[ConsulResponse] = 
      casecodec6(ConsulResponse.apply, ConsulResponse.unapply)( "CreateIndex","ModifyIndex","LockIndex","Key","Flags","Value")
    }
  }

  

}
