package com.lookout.borderpatrol.sessionx

import com.lookout.borderpatrol.sessionx.SecretStores.ConsulSecretStore
import com.lookout.borderpatrol.test._
import com.lookout.borderpatrol.test.sessionx.helpers

class SecretStoresSpec extends BorderPatrolSuite {
  import sessionx.helpers.{secretStore => store, _}, secrets._

  behavior of "SecretStoreApi"

  it should "give the current and previous Secret" in {
    store.current shouldBe current
    store.previous shouldBe previous
  }

  it should "always give a non-expired current secret" in {
    // set the current to an expired secret
    val tempStore = SecretStores.InMemorySecretStore(Secrets(previous, previous))
    tempStore.current should not be previous
    tempStore.current.expired shouldBe false
  }

  it should "find secrets if they exist" in {
    store.find(_.id == previous.id).value shouldBe previous
    store.find(_.id == current.id).value shouldBe current
    store.find(_.id == invalid.id) shouldBe None
  }

  //set up
  val consulConnection = helpers.MockConsulClient
  consulConnection.set("secretStore/secrets",SecretsEncoder.EncodeJson.encode(helpers.secrets.secrets).nospaces)
  val c = new ConsulSecretStore(consulConnection,10)

  it should "always return a secret" in {
    c.current should not be (null)
    c.previous should not be (null)
  }

  it should "rotate and return the current secret" in {
    Thread.sleep(1000)//allows the polling function to start appending
    c.find( _.id == current.id)
    c.current shouldBe current
  }

  it should "rotate when the secret is expiried" in {
    val consulConnection = helpers.MockConsulClient
    consulConnection.set("secretStore/secrets",SecretsEncoder.EncodeJson.encode(Secrets(current,previous)).nospaces)
    val c = new ConsulSecretStore(consulConnection,10)
    Thread.sleep(1000)//allows the polling function to start appending
    c.current shouldBe current

  }
}
