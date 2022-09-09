
package app

object StaticFiles extends cask.MainRoutes{

  @cask.get("/")
  def index() = cask.model.StaticFile("out/sigpow/devWebServer/www.dest/index.html", headers = Seq("Content-Type" -> "text/html"))
  
  @cask.get("/out.js")
  def outJs() = cask.model.StaticFile("out/sigpow/devWebServer/www.dest/out.js", headers = Seq("Content-Type" -> "text/javascript"))

  @cask.get("/out-bundle.js")
  def outBundleJs() = cask.model.StaticFile("out/sigpow/devWebServer/www.dest/out-bundle.js", headers = Seq("Content-Type" -> "text/javascript"))

  @cask.get("/out.js.map")
  def outMapJs() = cask.model.StaticFile("out/sigpow/devWebServer/www.dest/out.js.map", headers = Seq("Content-Type" -> "text/javascript"))

  @cask.get("/out-bundle.js.map")
  def outBundleMapJs() = cask.model.StaticFile("out/sigpow/devWebServer/www.dest/out-bundle.js.map", headers = Seq("Content-Type" -> "text/javascript"))

  initialize()
}