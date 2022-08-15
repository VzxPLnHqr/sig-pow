package vzxplnhqr.sigpow.native

import cats.effect._

object Main extends IOApp.Simple {
    
    val run = IO.println("hello from scala-native!") >> vzxplnhqr.sigpow.Main.run
}