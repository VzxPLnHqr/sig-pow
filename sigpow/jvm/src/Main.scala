package vzxplnhqr.sigpow.jvm

import cats.effect._

object Main extends IOApp.Simple {
    val run = IO.println("hello from jvm!") >> vzxplnhqr.sigpow.Main.run
}