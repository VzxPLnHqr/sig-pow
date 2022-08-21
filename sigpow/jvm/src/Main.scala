package vzxplnhqr.sigpow.jvm

import vzxplnhqr.sigpow.SigPowMainIOApp
import cats.effect._

object Main extends SigPowMainIOApp {
    val run = IO.println("hello from jvm!") >> runMain //>> vzxplnhqr.sigpow.Main.run
}