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
    val submittedIO = SignallingRef[IO].of(false).toResource
    val stdIO = (stdIn,stdOut,inputDisabledIO,submittedIO).tupled

    val render = stdIO.flatMap { case (in,out, disableIn, submitted ) =>
        val myprograms = new SigPowMainIOApp {
            private def _println(msg: String): IO[Unit] = out.update(_.appended(msg))
            private def _readLine: IO[String] = disableIn.set(false) >> submitted.waitUntil(_ == true) >> in.getAndSet("").flatTap(_ => disableIn.set(true) >> submitted.set(false))

            override def printlnIO(msg: String): IO[Unit] = _println(msg)
            override def printIO(msg: String): IO[Unit] = _println(msg)
            override def prompt(msg: String): IO[String] = _println(msg) *> _readLine.map(_.trim).flatTap(m => _println(s"you entered: $m"))
        }
        
        myprograms.runMain.background.flatMap { _ => 
            div(
                div(out.map(xs => ul(xs.map(x => li(pre(x)))))),
                input(
                    placeholder <-- disableIn.map{ case true => ""; case false => "type here and press enter"},
                    onKeyUp --> (_.filter(_.keyCode == 13).mapToTargetValue.foreach(v => in.set(v) >> submitted.set(true))),
                    disabled <-- disableIn,
                    value <-- in
                ),
                button(
                    "OK",
                    disabled <-- disableIn,
                    onClick --> (_.mapToTargetValue.foreach(_ => submitted.set(true)))
                )
            )
             
        }
    }
}