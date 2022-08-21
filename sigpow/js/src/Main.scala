package vzxplnhqr.sigpow.js

import vzxplnhqr.sigpow.SigPowMainIOApp

import cats.effect._
import scoin._
import scodec.bits.ByteVector

object Main extends SigPowMainIOApp {

    
    def run = IO.println("hello from javascript")
              >> runMain >> IO.unit

    /**
      * Helper functions and overrides
      */

    // in javascript (nodejs) we provide our own readline function using
    // fs2 streaming library. This is a hack, but seems to work.
    private def _readLine = fs2.io.stdin[IO].map(_.toChar).takeWhile(b => (b != '\r') && (b != '\n')).compile.toList.map(l => String.valueOf(l.toArray))  //.head.compile.toList.flatMap(l => IO(l.head))
    private def _print(msg: String) = fs2.Stream.eval(IO(msg)).through(fs2.io.stdoutLines(java.nio.charset.StandardCharsets.UTF_8)).compile.drain
    
    override def prompt(msg: String): IO[String] = _print(msg + " ") *> _readLine.map(_.trim).flatTap(m => _print(s"you entered: $m \n"))

}