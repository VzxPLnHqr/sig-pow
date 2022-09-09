package vzxplnhqr.sigpow.js

import vzxplnhqr.sigpow.SigPowMainIOApp

//import cats.effect._
import scoin._
import scodec.bits.ByteVector

import calico.*
import calico.unsafe.given
import calico.dsl.io.*
import calico.syntax.*
import cats.effect.*
import cats.effect.syntax.all.*
import fs2.*
import fs2.concurrent.*
import scala.concurrent.duration.DurationInt
import cats.syntax.all.*

object Main extends IOWebApp {

    val stdOut = SignallingRef[IO].of(List.empty[String]).toResource
    val stdIn = SignallingRef[IO].of("").toResource
    val inputDisabledIO = SignallingRef[IO].of(true).toResource
    val stdIO = (stdIn,stdOut,inputDisabledIO).tupled

    val render = stdIO.flatMap { case (in,out, disableIn ) =>
        val myprograms = new SigPowMainIOApp {
            private def _println(msg: String): IO[Unit] = out.update(_.appended(msg))
            private def _readLine: IO[String] = disableIn.set(false) >> in.waitUntil(_.nonEmpty) >> in.getAndSet("").flatTap(_ => disableIn.set(true))

            override def printlnIO(msg: String): IO[Unit] = _println(msg)
            override def printIO(msg: String): IO[Unit] = _println(msg)
            override def prompt(msg: String): IO[String] = _println(msg) *> _readLine.map(_.trim).flatTap(m => _println(s"you entered: $m"))
        }
        
        val prog2 = {
          def _println(msg: String): IO[Unit] = out.update(_.appended(msg))
          def _readLine: IO[String] = disableIn.set(false) >> in.waitUntil(_.nonEmpty) >> in.getAndSet("").flatTap(_ => disableIn.set(true))
          _println("hello world")
        }
        
        myprograms.runMain.background.flatMap { _ => 
            div(
                div(out.map(xs => ul(xs.map(li(_))))),
                input(
                    placeholder <-- disableIn.map{ case true => ""; case false => "type here and press enter"},
                    onKeyUp --> (_.filter(_.keyCode == 13).mapToTargetValue.foreach(in.set)),
                    disabled <-- disableIn,
                    value <-- in
                )
            )
             
        }
    }
}