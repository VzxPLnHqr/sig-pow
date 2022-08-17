package vzxplnhqr.sigpow.js

import cats.effect._
import scoin._
import scodec.bits.ByteVector

object Main extends IOApp.Simple {
    val run = IO.println("hello from javascript")
              >> IO.println(s"hash of abc is ${Crypto.sha256(ByteVector("abc".getBytes))}")//>> vzxplnhqr.sigpow.Main.run
}