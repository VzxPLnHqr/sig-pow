import mill._, scalalib._, scalanativelib._

import coursier.maven.MavenRepository

// which versions of Scala to target?
val scalaVersions = List("3.1.3")
val scalaNativeVersions = scalaVersions.map((_,"0.4.5"))

// a general module to capture as many "shared" things as possible
abstract class SigPowMainModule(val crossScalaVersion: String) extends CrossScalaModule {
  
  def offset: os.RelPath = os.rel
  def millSourcePath = super.millSourcePath / offset

  def sources = T.sources(
    super.sources()
      .flatMap(source =>
        Seq(
          PathRef(source.path / os.up / source.path.last),
          PathRef(source.path / os.up / os.up / source.path.last)
        )
      )
  )

  // by default we choose the main class within the shared sources
  // override this in platform-specific projects
  def mainClass = Some("vzxplnhqr.sigpow.Main")
}

object sigpow extends Module {

  object jvm extends Cross[JvmSigPowModule](scalaVersions: _*)
  class JvmSigPowModule extends SigPowMainModule(crossScalaVersion="3.1.3") with ScalaModule {
      def ivyDeps = Agg(
      ivy"org.typelevel::cats-effect:3.3.12", // for purely functional programming
      ivy"com.fiatjaf::scoin:0.1.0-SNAPSHOT", // for btc data structures
      ivy"org.typelevel::spire:0.18.0" // for number types
      //ivy"org.slf4j:slf4j-nop:1.7.12" // to get rid of a weird logging warning
    )

    def mainClass = Some("vzxplnhqr.sigpow.jvm.Main")

    // make sure to include both the shared sources and the platform specific sources
    def sources = T.sources(super.sources() ++ Seq(PathRef(build.millSourcePath / "sigpow" / "jvm" / "src")))
  }

  object native extends Cross[NativeSigPowModule](scalaNativeVersions: _*)
  class NativeSigPowModule extends SigPowMainModule(crossScalaVersion="3.1.3") with ScalaNativeModule {
    def offset = os.up
    def scalaNativeVersion = "0.4.5"
    //def logLevel = NativeLogLevel.Info // optional
    //def releaseMode = ReleaseMode.Debug // optional
    
    def ivyDeps = Agg(
      ivy"com.armanbilge::cats-effect_native0.4:3.4-f28b163-SNAPSHOT",
      ivy"com.fiatjaf::scoin_native0.4:0.1.0-SNAPSHOT", // for btc data structures
      ivy"org.typelevel::spire_native0.4:0.18.0" // for number types
    )

    // add snapshots repositories (needed for testing cutting edge things like cats-effect with scala-native)
    def repositoriesTask = T.task { super.repositoriesTask() ++ Seq(
      MavenRepository("https://s01.oss.sonatype.org/content/repositories/snapshots")
    ) }

    def mainClass = Some("vzxplnhqr.sigpow.native.Main")

    // include platform specific sources
    def sources = T.sources(super.sources() ++ Seq(PathRef(build.millSourcePath / "sigpow" / "native" / "src")))
  }
}