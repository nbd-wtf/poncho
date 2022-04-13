// Set to false or remove if you want to show stubs as linking errors
nativeLinkStubs := false

enablePlugins(ScalaNativePlugin)

name                  := "pokemon"
organization          := "fiatjaf"
scalaVersion          := "3.1.1"
version               := "0.1.0"
libraryDependencies   ++= Seq(
  "com.github.lolgab" %%% "native-loop-core" % "0.2.1",
  "com.lihaoyi" %%% "castor" % "0.2.1",
  "com.lihaoyi" %%% "ujson" % "1.5.0"
)
