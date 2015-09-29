package com.lookout.borderpatrol.sessionx

//import org.jboss.netty.handler.codec.http.{DefaultHttpRequest, HttpRequest, HttpResponse, HttpVersion, HttpMethod}
import com.twitter.finagle.Service
import com.twitter.finagle.builder.ClientBuilder
import com.twitter.finagle.http._
import com.twitter.io.Charsets
import org.jboss.netty.buffer.ChannelBuffers
import java.util.Base64.Decoder._
import java.nio.charset._
import argonaut._, Argonaut._
import com.twitter.util.Future
import com.twitter.finagle.{Httpx, Service}
import com.twitter.finagle.httpx



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
    
    
    def current: Future[Option[Secret]] = {
      val req = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/v1/kv/secretStore/current")
      val f = consul(req) 

    
      f onSuccess { res =>
       println("got response" );
       val jsonReponse = res.getContent.toString(Charsets.Utf8) ;
       val decodedJSONList = jsonReponse.decodeOption[List[ConsulSecretStore.ConsulResponse]].getOrElse(Nil)
       val decodedString = base64Decode(decodedJSONList.headOption.get.Value)
       val json: Option[Json] = Parse.parseOption(decodedString)
      Some(SecretEncoder.EncodeJson.decode(json.get) )


      } onFailure { exc =>
        println("failed :-(" + exc);
        //Secret()

      }
     
      
    }

    def base64Decode(s: String): String ={
      val decodedArray = java.util.Base64.getDecoder().decode(s);
      new String(decodedArray, StandardCharsets.UTF_8)
    }

    def previous: Secret = {
       /*
      TODO
      */
      Secret()
    }

    def find(f: Secret => Boolean): Option[Secret] = {
      /*
      TODO
      */
      None
    }

    def update(newSecret: Secret): Boolean ={
      val encodedSecret = SecretEncoder.EncodeJson.encode(newSecret)
      println(encodedSecret)
      val data = ChannelBuffers.copiedBuffer(encodedSecret.nospaces, Charsets.Utf8)
      val req: HttpRequest= RequestBuilder()
        .url("http://localhost:8500/v1/kv/secretStore/current")
        .buildPut(data)
      
    
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
    
    case class ConsulResponse(CreateIndex: Int, ModifyIndex: Int,LockIndex: Int,Key: String, Flags: Int, Value: String)
    object ConsulResponse {
      implicit def ConsulResponseCodec: CodecJson[ConsulResponse] = 
      casecodec6(ConsulResponse.apply, ConsulResponse.unapply)( "CreateIndex","ModifyIndex","LockIndex","Key","Flags","Value")
    }
  }

  

}
