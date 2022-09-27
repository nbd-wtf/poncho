enablePlugins(ScalaNativePlugin)

scalaVersion          := "3.1.3"
libraryDependencies   ++= Seq(
  "org.scodec" %%% "scodec-bits" % "1.1.32",
  "org.scodec" %%% "scodec-core" % "2.2.0",
  "com.lihaoyi" %%% "upickle" % "1.6.0",
  "com.lihaoyi" %%% "ujson" % "1.6.0",
  "com.fiatjaf" %%% "scoin" % "0.3.0",
  "com.fiatjaf" %%% "nlog" % "0.1.0",
  "com.fiatjaf" %%% "sn-unixsocket" % "0.1.0",
  "com.github.lolgab" %%% "native-loop-core" % "0.2.1",
  "com.softwaremill.quicklens" %%% "quicklens" % "1.8.8",

  "com.lihaoyi" %%% "utest" % "0.7.11" % Test
)
testFrameworks  += new TestFramework("utest.runner.Framework")

nativeConfig := {
  import scala.scalanative.build.Mode
  import scala.scalanative.build.LTO

  val conf = nativeConfig.value
  if (sys.env.get("SN_RELEASE").contains("fast"))
    conf.withOptimize(true).withLTO(LTO.thin).withMode(Mode.releaseFast)
  else conf
}

nativeConfig := {
  import scala.sys.process._

  // build libuv
  Process("git clone https://github.com/libuv/libuv", target.value) #&& Process("./autogen.sh", target.value / "libuv") #&& Process("./configure", target.value / "libuv") #&& Process("make", target.value / "libuv") !

  // build libsecp256k1
  Process("git clone https://github.com/bitcoin-core/secp256k1", target.value) #&& Process("./autogen.sh", target.value / "secp256k1") #&& Process("./configure --enable-module-schnorrsig --enable-module-recovery", target.value / "secp256k1") #&& Process("make", target.value / "secp256k1") !

  val conf = nativeConfig.value

  conf
    .withLinkingOptions(
      conf.linkingOptions ++ Seq(
        "-static",
        s"-L${target.value}/secp256k1/.libs",
        "-lsecp256k1",
        s"-L${target.value}/libuv/.libs",
        "-luv",
      )
    )
}
