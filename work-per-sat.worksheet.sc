// this worksheet gets ahold of a type safe rpc client for bitcoind
// using the bitcoin-s library
import $ivy.`org.bitcoin-s::bitcoin-s-bitcoind-rpc:1.9.1`
import org.bitcoins._

// for storing/retreiving secrets and config information in application.conf
import com.typesafe.config.ConfigFactory

// simple config object allowing us to keep secret values out of
// this code. Place sensitive and config values in application.conf
object Config {
    // surely a better way to do this, but oh well.
    // application.conf must contain the following settings:
    //    bitcoin-rpc.rpcuser = "user"
    //    bitcoin-rpc.rpcpassword = "password"
    val defaultConfig = ConfigFactory.parseString(scala.io.Source.fromFile("./application.conf").getLines().mkString("\n"))
    val bitcoind_rpcuser = defaultConfig.getString("bitcoind-rpc.rpcuser")
    val bitcoind_rpcpassword = defaultConfig.getString("bitcoind-rpc.rpcpassword")
}

implicit val ec: concurrent.ExecutionContext = concurrent.ExecutionContext.global
implicit val actorSystem = akka.actor.ActorSystem("System")

val authCredentials = rpc.config.BitcoindAuthCredentials.PasswordBased(
  username = Config.bitcoind_rpcuser,
  password = Config.bitcoind_rpcpassword
)

// do not forget to open a port locally
// ssh -L 8332:localhost:8332 my-cool-user@my-cool-website.com
val bitcoindInstance = {
  rpc.config.BitcoindInstanceRemote(
    network = core.config.MainNet,
    uri = new java.net.URI(s"http://localhost:${core.config.MainNet.port}"),
    rpcUri = new java.net.URI(s"http://localhost:${core.config.MainNet.rpcPort}"),
    authCredentials = authCredentials
  )
}

// the object of our desire, finally! A type-safe rpc client. Most of the methods
// return a Future[A] for some A.
val rpcCli = rpc.client.common.BitcoindRpcClient(bitcoindInstance)

// this file can be run via ammonite: `amm -p work-per-sat.worksheet.sc`
// and then within the ammonite repl, a typesafe rpc client is available
// and things like this are possible:
// 
// rpcCli.getBlockCount.foreach(n => println(s"$n blocks found in the chain"))