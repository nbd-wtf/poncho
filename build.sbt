enablePlugins(ScalaNativePlugin)

scalaVersion          := "3.2.0"
libraryDependencies   ++= Seq(
  "org.scodec" %%% "scodec-bits" % "1.1.32",
  "org.scodec" %%% "scodec-core" % "2.2.0",
  "io.circe" %%% "circe-core" % "0.14.3",
  "io.circe" %%% "circe-generic" % "0.14.3",
  "io.circe" %%% "circe-parser" % "0.14.3",
  "com.fiatjaf" %%% "scoin" % "0.5.0",
  "com.fiatjaf" %%% "nlog" % "0.1.0",
  "com.fiatjaf" %%% "sn-unixsocket" % "0.1.0",
  "com.github.lolgab" %%% "native-loop-core" % "0.2.1",
  "com.softwaremill.quicklens" %%% "quicklens" % "1.8.8"
)

nativeConfig := {
  import scala.scalanative.build.Mode
  import scala.scalanative.build.LTO

  val conf = nativeConfig.value
  if (sys.env.get("SN_RELEASE").contains("fast"))
    conf.withOptimize(true).withLTO(LTO.thin).withMode(Mode.releaseFast)
  else conf
}

nativeConfig := {
  val conf = nativeConfig.value

  if (sys.env.get("SN_LINK").contains("static"))
    conf
      .withLinkingOptions(
        conf.linkingOptions ++ Seq(
          "-static",
          "-lsecp256k1",
          "-luv",
        )
      )
  else conf
}
