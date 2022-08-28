enablePlugins(ScalaNativePlugin)

name                  := "poncho"
organization          := "fiatjaf"
scalaVersion          := "3.1.3"
version               := "0.2.0"
libraryDependencies   ++= Seq(
  "org.scodec" %%% "scodec-bits" % "1.1.32",
  "org.scodec" %%% "scodec-core" % "2.2.0",
  "com.lihaoyi" %%% "upickle" % "1.6.0",
  "com.lihaoyi" %%% "ujson" % "1.6.0",
  "com.fiatjaf" %%% "scoin" % "0.3.0",
  "com.fiatjaf" %%% "nlog" % "0.1.0",
  "com.fiatjaf" %%% "sn-unixsocket" % "0.1.0",
  "com.fiatjaf" %%% "sn-chacha20poly1305" % "0.2.1",
  "com.github.lolgab" %%% "native-loop-core" % "0.2.1",
  "com.softwaremill.quicklens" %%% "quicklens" % "1.8.8",

  "com.lihaoyi" %%% "utest" % "0.7.11" % Test
)
testFrameworks  += new TestFramework("utest.runner.Framework")
nativeLinkStubs := true

// _for-release_ nativeMode := "release-fast"
// _for-release_ nativeLTO := "thin"
// _for-armv6_ nativeConfig ~= { _.withTargetTriple("armv6-pc-linux-unknown") }
