package vzxplnhqr.sigpow.js

import cats.effect._
import scoin._
import scodec.bits.ByteVector

object Main extends IOApp.Simple {

    def readLine = fs2.io.stdinUtf8[IO].head.compile.toList.flatMap(l => IO(l.head))
    val run = IO.println("hello from javascript")
              //>> IO.println(s"hash of abc is ${Crypto.sha256(ByteVector("abc".getBytes))}")
              >> readLine.flatMap(m => IO.println(s"you said: $m"))
              >> readLine.flatMap(m => IO.println(s"then you said: $m")) //>> vzxplnhqr.sigpow.Main.run

}