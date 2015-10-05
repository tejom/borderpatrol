package com.lookout.borderpatrol.sessionx

import org.jboss.netty.handler.codec.http.{DefaultHttpRequest, HttpRequest, HttpResponse, HttpVersion, HttpMethod}
import com.twitter.finagle.Service
import com.twitter.finagle.builder.ClientBuilder
import com.twitter.io.{Charsets, Buf}
import org.jboss.netty.buffer.ChannelBuffers
//import java.util.Base64.Decoder._
import java.nio.charset._
import argonaut._, Argonaut._
import com.twitter.util.{Future , Await }
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

  class ConsulSecretStore(consul: ConsulConnection ,poll: Int) extends SecretStoreApi {
    val cache = new ConsulSecretCache(poll,consul)
    /**
    *Create a new thread that will check the consul server for updates
    **/
    def startPolling() ={
      new Thread( cache ).start
    }
    
    /**
    *Get the current secret from the cache then tries to get a secret from the server it self. 
    * Im not sure if the or else is neccesary since startPolling would be doing this anyway
    *Throws and exception if the cache is empty and
    **/
    def current: Secret = {
      cache.getCurrent.getOrElse( cache.pollCurrent.getOrElse({ 
        throw new Exception("Failed getting current secret from cache and consul Server")}))
    }
    /**
    *Get the previous secret from the cache then tries to get a secret from the server it self. 
    * Im not sure if the or else is neccesary since startPolling would be doing this anyway
    *Throws and exception if the cache is empty and
    **/
     def previous: Secret = {
      cache.getPrevious.getOrElse( cache.pollPrevious.getOrElse({ 
        throw new Exception("Failed getting previous secret from cache and consul Server")}))
    }
    //this probbaly will have the same implementation as the inmemorystore
    def find(f: Secret => Boolean): Option[Secret] = {
      if (f(current)) Some(current)
      else if (f(previous)) Some(previous)
      else None
    }
    /**
    * Roattes the current to previous, Updates the current secret to the paramater on the consul server and returns Unit
    **/
    def update(newSecret: Secret): Unit ={
      val currentDataString = Await.result(consul.getValue("/v1/kv/secretStore/current") )
      val newEncodedSecret = SecretEncoder.EncodeJson.encode(newSecret)
      println(newEncodedSecret)
      currentDataString match {
        case Success(s) => consul.setValue("secretStore/previous",currentDataString.get)
        case Failure(e) => println(" Failed trying to get Current Secret from consul. Exception: " + e)
      }
      consul.setValue("secretStore/current",newEncodedSecret.nospaces)
    }
  }
  /**
  *Polls the consul server and updates an inmemory cache for SecretStore
  **/
  class ConsulSecretCache(poll: Int, consul: ConsulConnection) extends Runnable  {
    val cache = scala.collection.mutable.HashMap.empty[String,Secret] 
    /**scala.collection.mutable package is disabled is the warning this gives
    *Im not sure how to implement this without this. Everything ive read on cache and memoization(not sure that completly applies
    *but its simmilar) uses a mutable map
    **/

    /**
    *Continously poll the consul server at the interval passed to the Class when it was created
    *updates the cache based on what it finds
    *Calling this using the function in consul secret store will put this in a new thread
    **/
    def run() = {
      while(true){
        for {
          c <- pollCurrent
          p <- pollPrevious
        } yield cache+=("current" -> c, "previous" -> p)
        Thread.sleep( poll * 1000)
      }
    }
    /**
    *Get the secret at current from the cache or returns None
    **/
    def getCurrent: Option[Secret] = {
      cache.get("current") 
    }
    /**
    *Get the secret at previous from the cache or returns None
    **/
     def getPrevious: Option[Secret] = {
      cache.get("previous")
    }
    /**
    *Get the secret at current from the consul server or returns None
    **/
    def pollCurrent: Option[Secret] = {
      val r = consul.getValue("/v1/kv/secretStore/current")
      Await.result(r) match {
        case Success(a) => secretTryFromString(a).toOption
        case Failure(e)=> None
      }
    }
     /**
    *Get the secret at previous from the consul server or returns None
    **/
    def pollPrevious: Option[Secret] = {
      val r = consul.getValue("/v1/kv/secretStore/previous") 
      Await.result(r) match {
        case Success(a) => secretTryFromString(a).toOption
        case Failure(e)=> None
      }
    }
    /**
    *Returns a Try[Secret] from json as a [String]
    **/
    private def secretTryFromString(s: String): Try[Secret] = {
      
      val json: Option[Json] = Parse.parseOption(s)
      val tryResponse = SecretEncoder.EncodeJson.decode(json.get)
      //compiler warns about get here. Not sure what the best way to fail is though
      tryResponse
    }

  }
  /**
  *class to interface with consul kv store
  **/
  class ConsulConnection(consul: Service[httpx.Request, httpx.Response],host: String) {
     
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
    *Get just the decoded value for a key from consul as Future[String]. To get the full json response from Consul
    *use getConsulRepsonse
    **/
    def getValue[A,B](k: String): Future[Try[String]] = {
      val s = getConsulResponse(k)
      val decodedJSONList = s.map(a => a.decodeOption[List[ConsulSecretStore.ConsulResponse]].getOrElse( List() ))
       decodedJSONList.map(a=> Try(base64Decode(a.headOption.get.Value) ) ) 
       //compiler warns about get here. Not sure what the best way to fail is though     
    }
    /**
    *Set the given key to the given Value. Both are strings
    **/
    def setValue(k: String, v: String): Future[httpx.Response] = {
      val currentData = Buf.Utf8(v)
      val update :httpx.Request = httpx.RequestBuilder()
         .url(s"http://$host:8500/v1/kv/$k")
         .buildPut(currentData)
      consul(update)
    }
  }
  object ConsulSecretStore{

    def apply(consulUrl: String, consulPort: String,poll: Int):ConsulSecretStore = {
      val apiUrl = s"$consulUrl:$consulPort"
      println(apiUrl)
      val client: Service[httpx.Request, httpx.Response] = Httpx.newService(apiUrl)
      val consulConnection = new ConsulConnection(client,consulUrl)

      val c = new ConsulSecretStore(consulConnection,poll)
      c
    }
    
    case class ConsulResponse(CreateIndex: Int, ModifyIndex: Int,LockIndex: Int,Key: String, Flags: Int, Value: String)
    object ConsulResponse {
      implicit def ConsulResponseCodec: CodecJson[ConsulResponse] = 
      casecodec6(ConsulResponse.apply, ConsulResponse.unapply)( "CreateIndex","ModifyIndex","LockIndex","Key","Flags","Value")
    }
  } 
}
