import java.io._
import java.util.zip.ZipInputStream

import geny.Generator
import mill._
import mill.define.Target
import mill.scalajslib._


object WebpackLib {
  case class JsDeps(dependencies: List[(String, String)] = Nil,
                    devDependencies: List[(String, String)] = Nil,
                    jsSources: Map[String, String] = Map.empty) {
    def ++(that: JsDeps): JsDeps =
      JsDeps(
        dependencies ++ that.dependencies,
        devDependencies ++ that.devDependencies,
        jsSources ++ that.jsSources)
  }

  object JsDeps {
    def apply(dependencies: (String, String)*): JsDeps = JsDeps(dependencies = dependencies.toList)
    implicit def rw: upickle.default.ReadWriter[JsDeps] = upickle.default.macroRW
  }

  case class WebpackParams(inputFile: os.Path,
                           jsDeps: JsDeps,
                           outputDirectory: os.Path,
                           opt: Boolean,
                           libraryName: Option[String]) {
    lazy val copiedInputFile = outputDirectory / inputFile.last
  }

  private def writePkgJson(params: WebpackParams,
                           deps: JsDeps,
                           webpackVersion: String,
                           webpackCliVersion: String,
                           webpackDevServerVersion: String) = {
    val webpackDevDependencies = Seq(
      "webpack" -> webpackVersion,
      "webpack-cli" -> webpackCliVersion,
      "webpack-dev-server" -> webpackDevServerVersion,
      "source-map-loader" -> "0.2.3"
    )
    os.write.over(
      params.outputDirectory / "package.json",
      ujson.Obj(
        //"type" -> "module",
        "dependencies" -> deps.dependencies,
        "devDependencies" -> (deps.devDependencies ++ webpackDevDependencies)
      ).render(2) + "\n"
    )
  }

  @scala.annotation.tailrec
  private def readAllBytes(in: InputStream,
                           buffer: Array[Byte] = new Array[Byte](8192),
                           out: ByteArrayOutputStream = new ByteArrayOutputStream): String = {
    val byteCount = in.read(buffer)
    if (byteCount < 0)
      out.toString
    else {
      out.write(buffer, 0, byteCount)
      readAllBytes(in, buffer, out)
    }
  }

  private def jsDepsFromJar(jar: File): Seq[JsDeps] = {
    val stream = new ZipInputStream(new BufferedInputStream(new FileInputStream(jar)))
    try
      Iterator.continually(stream.getNextEntry)
        .takeWhile(_ != null)
        .collect {
          case z if z.getName == "NPM_DEPENDENCIES"                              =>
            val contentsAsJson = ujson.read(readAllBytes(stream)).obj

            def dependenciesOfType(key: String): List[(String, String)] =
              contentsAsJson.getOrElse(key, ujson.Arr()).arr.flatMap(_.obj.map { case (s, v) => s -> v.str }).toList

            JsDeps(
              dependenciesOfType("compileDependencies") ++ dependenciesOfType("compile-dependencies"),
              dependenciesOfType("compileDevDependencies") ++ dependenciesOfType("compile-devDependencies")
            )
          case z if z.getName.endsWith(".js") && !z.getName.startsWith("scala/") =>
            JsDeps(Nil, Nil, Map(z.getName -> readAllBytes(stream)))
        }
        .toList
    finally
      stream.close()
  }

  private def writeEntrypoint0(dst: os.Path, depNames: Iterable[String]) = {
    val path = dst / "entrypoint.js"
    os.write.over(
      path,
      s"""
         |module.exports = {
         |  "require": (function(moduleName) {
         |    return {
         |      ${depNames.map { name => s"'$name': require('$name')" }.mkString(",\n      ")}
         |    }[moduleName]
         |  })
         |}
         |""".stripMargin
        .trim
    )
    PathRef(path)
  }

  val webpackConfigFilename = "webpack.config.js"

  def writeWpConfig(params: WebpackParams, bundleFilename: String) = {
    val libraryOutputCfg =
      params.libraryName.map(n => Map("library" -> n, "libraryTarget" -> "var")).getOrElse(Map.empty)
    val outputCfg =
      libraryOutputCfg ++ Map("path" -> params.outputDirectory.toString, "filename" -> bundleFilename)
    os.write.over(
      params.outputDirectory / webpackConfigFilename,
      "module.exports = " + ujson.Obj(
        "mode" -> (if (params.opt) "production" else "development"),
        "devtool" -> "source-map",
        "entry" -> params.copiedInputFile.toString,
        "output" -> ujson.Obj.from(outputCfg.view.mapValues(ujson.Str)),
        "resolve" -> ujson.Obj("fallback" -> ujson.Obj("crypto" -> false))
      ).render(2) + ";\n"
    )
  }

  trait ScalaJSDepsModule extends ScalaJSModule {
    def moduleDepJsDepsTarget =
      T.sequence(recursiveModuleDeps.collect { case mod: ScalaJSDepsModule => mod.jsDeps })

    def jsDeps: Target[JsDeps] = T {
      val jsDepsFromIvyDeps =
        resolveDeps(transitiveIvyDeps)().iterator.toList.flatMap(pathRef => jsDepsFromJar(pathRef.path.toIO))
      val allJsDeps = jsDepsFromIvyDeps ++ moduleDepJsDepsTarget()
      allJsDeps.foldLeft(JsDeps())(_ ++ _)
    }
  }

  trait ScalaJSWebpackBaseModule extends ScalaJSDepsModule {
    def webpackVersion: Target[String] = "5.74.0" //"4.17.1"
    def webpackCliVersion: Target[String] = "4.10.0" //"3.1.0"
    def webpackDevServerVersion: Target[String] = "4.10.0" //"3.1.7"

    def writePackageJson = T.task { params: WebpackParams =>
      writePkgJson(params, params.jsDeps, webpackVersion(), webpackCliVersion(), webpackDevServerVersion())
    }

    def bundleFilename = T {
      "out-bundle.js"
    }

    def webpack = T.task { params: WebpackParams =>
      val _bundleFilename = bundleFilename()
      if (params.inputFile != params.copiedInputFile)
        os.copy.over(params.inputFile, params.copiedInputFile)
      params.jsDeps.jsSources foreach { case (n, s) => os.write.over(params.outputDirectory / n, s) }
      writeWpConfig(params, _bundleFilename)
      writePackageJson().apply(params)
      val logger = T.ctx().log
      val npmInstall = os.proc("npm", "install").call(params.outputDirectory)
      logger.debug(npmInstall.out.text())
      val webpackPath = params.outputDirectory / "node_modules" / "webpack" / "bin" / "webpack"
      val webpack =
        os.proc("node", webpackPath, "--bail", "--profile", "--config", webpackConfigFilename)
          .call(params.outputDirectory)
      logger.debug(webpack.out.text())
      if (params.inputFile != params.copiedInputFile)
      os.remove(params.copiedInputFile)
      List(
        PathRef(params.outputDirectory / _bundleFilename),
        PathRef(params.outputDirectory / (_bundleFilename + ".map"))
      )
    }

    def devWebpack: Target[Seq[PathRef]]
    def prodWebpack: Target[Seq[PathRef]]
  }

  trait ScalaJSWebpackApplicationModule extends ScalaJSWebpackBaseModule {
    override def devWebpack: Target[Seq[PathRef]] = T.persistent {
      webpack().apply(WebpackParams(fastOpt().path, jsDeps(), T.ctx().dest, opt = false, None))
    }

    override def prodWebpack: Target[Seq[PathRef]] = T.persistent {
      webpack().apply(WebpackParams(fullOpt().path, jsDeps(), T.ctx().dest, opt = true, None))
    }
  }

  trait ScalaJSWebpackLibraryModule extends ScalaJSWebpackBaseModule {
    private val regex = """require\("([^"]*)"\)""".r

    def writeEntrypoint = T.task { (src: PathRef, dest: os.Path) =>
      val requires =
        os.read.lines.stream(src.path)
          .flatMap(line => Generator.from(regex.findAllMatchIn(line).map(_.group(1))))
          .toList
      writeEntrypoint0(dest, requires)
    }

    def webpackLibraryName = T {
      "app"
    }

    override def devWebpack: Target[Seq[PathRef]] = T.persistent {
      val dest = T.ctx().dest
      val deps = jsDeps()
      val src = fastOpt()
      val entrypoint = writeEntrypoint().apply(src, dest).path
      webpack().apply(WebpackParams(entrypoint, deps, dest, opt = false, Some(webpackLibraryName()))) :+ src
    }

    override def prodWebpack: Target[Seq[PathRef]] = T.persistent {
      val dest = T.ctx().dest
      val deps = jsDeps()
      val src = fullOpt()
      val entrypoint = writeEntrypoint().apply(src, dest).path
      webpack().apply(WebpackParams(entrypoint, deps, dest, opt = true, Some(webpackLibraryName()))) :+
        src
    }
  }
}