package vzxplnhqr.sigpow.native

import vzxplnhqr.sigpow.SigPowMainIOApp
import cats.effect._

object Main extends SigPowMainIOApp {
    
    val run = IO.println("hello from scala-native!") >> runMain
}