import mill._, scalalib._, scalanativelib._, scalajslib._, scalajslib.api._

import $file.Webpack
import Webpack.WebpackLib._

import coursier.maven.MavenRepository

// which versions of Scala to target?
val scalaVersions = List("3.1.3")
val scalaNativeVersions = scalaVersions.map((_,"0.4.5"))
val scalaJSVersions = scalaVersions.map((_, "1.10.1"))

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

  object js extends Cross[JsSigPowModule](scalaJSVersions: _*)
  class JsSigPowModule extends SigPowMainModule(crossScalaVersion="3.1.3") with ScalaJSWebpackApplicationModule {
    def offset = os.up
    def ivyDeps = Agg(
      //ivy"org.typelevel::cats-effect::3.3.12",
      ivy"com.fiatjaf::scoin::0.3.0-SNAPSHOT",//0.2-4dae8f0-SNAPSHOT", //0.2-f46c947-SNAPSHOT", //0.2-cc40d68-SNAPSHOT",//0.2-b52c5f3-SNAPSHOT", //0.2-2817cfc-SNAPSHOT",
      ivy"org.typelevel::spire::0.18.0",
      ivy"co.fs2::fs2-io::3.2.12",
      ivy"com.armanbilge::calico::0.1.1" //for building web ui
    )
    def moduleKind = T { ModuleKind.CommonJSModule }
    def scalaJSVersion = "1.10.1"

    def mainClass = Some("vzxplnhqr.sigpow.js.Main")

    override def jsDeps = T {
      super.jsDeps() ++ JsDeps(
        //"crypto-browserfy" -> "3.12.0", // weird webpack requirement?
        "@noble/secp256k1" -> "1.6.3", // required by scoin
        "hash.js" -> "1.1.7",          // required by scoin
        "chacha" -> "2.1.0"            // required by scoin
      )
    }
    // include platform specific sources
    def sources = T.sources(super.sources() ++ Seq(PathRef(build.millSourcePath / "sigpow" / "js" / "src")))
    //def sources = T.sources(Seq(PathRef(build.millSourcePath / "sigpow" / "js" / "src")))

    def npmInstall = T {
      val params = WebpackParams(fastOpt().path, jsDeps(), T.ctx().dest, opt = false, None)
      val _bundleFilename = bundleFilename()
      if (params.inputFile != params.copiedInputFile)
        os.copy.over(params.inputFile, params.copiedInputFile)
      
      //params.jsDeps.jsSources foreach { case (n, s) => os.write.over(params.outputDirectory / n, s) }
      //writeWpConfig(params, _bundleFilename)
    
      writePackageJson().apply(params)
      val logger = T.ctx().log
      val npmInstall_proc = os.proc("npm", "install").call(params.outputDirectory)
      logger.debug(npmInstall_proc.out.text())
      PathRef(T.dest)
    }

    // currently broken because this js project right now targets browser, not nodejs
    def runNode() = T.command {
      val outDir = npmInstall().path
      val outFile = outDir / fastOpt().path.last
      os.proc("node", outFile).call(cwd=outDir,stdin=os.Inherit,stdout=os.Inherit,stderr=os.Inherit)
    }
  }

  object devWebServer extends ScalaModule {
    def scalaVersion = "3.1.3"

    def ivyDeps = Agg(
      ivy"com.lihaoyi::cask:0.8.3"
    )

    def indexHtml = T.source {
      PathRef(js("3.1.3","1.10.1").millSourcePath / "src" / "resources" / "index.html")
    }

    def www = T {
      val dir = T.ctx().dest
      PathRef(dir)
    }

    override def compile = T {
      val publicDir = www().path
      js("3.1.3","1.10.1").devWebpack.apply().foreach {
        pathref => os.copy.over(pathref.path, publicDir / pathref.path.last)
      }
      os.copy.over(js("3.1.3","1.10.1").fastOpt().path / os.up / "out.js.map", publicDir / "out.js.map")
      val indexHtmlPath =indexHtml().path
      val indexHtmlFileName = indexHtmlPath.last
      os.copy.over(indexHtmlPath, publicDir / indexHtmlFileName )
      super.compile.apply()
    }

    def updateDemo = T {
      val demoDir = millSourcePath / os.up / os.up / "www"
      js("3.1.3","1.10.1").devWebpack.apply().foreach {
        pathref => os.copy.over(pathref.path, demoDir / pathref.path.last)
      }
      val indexHtmlPath =indexHtml().path
      val indexHtmlFileName = indexHtmlPath.last
      os.copy.over(indexHtmlPath, demoDir / indexHtmlFileName )
      PathRef(demoDir)
      //note: this only updates the local demo. To push demo to github pages
      // 1. commit any demo related changes in www/ directory
      // 2. run `git subtree push --prefix www origin gh-pages`
    }

    // push the latest committed demo up to github pages
    def pushDemo() = T.command {
      val rootDir = millSourcePath / os.up / os.up
      os.proc("git","subtree","push","--prefix","www","origin","gh-pages")
        .call(cwd=rootDir,stdin=os.Inherit,stdout=os.Inherit,stderr=os.Inherit)
    }
  }
}