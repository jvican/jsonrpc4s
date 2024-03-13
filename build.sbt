lazy val scala212 = "2.12.14"
lazy val scala213 = "2.13.6"

inThisBuild(
  List(
    organization := "me.vican.jorge",
    homepage := Some(url("https://github.com/jvican/json4pc4")),
    licenses := Seq(
      "Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")
    ),
    scmInfo := Some(
      ScmInfo(
        url("https://github.com/jvican/jsonrpc4s"),
        "scm:git:git@github.com:jvican/jsonrpc4s.git"
      )
    ),
    developers := List(
      Developer(
        "jvican",
        "Jorge Vicente Cantero",
        "jorgevc@fastmail.es",
        url("https://jvican.github.io/")
      )
    ),
    scalaVersion := scala213,
    scalacOptions ++= List(
      "-Yrangepos",
      "-deprecation",
      "-Xlint"
    ),
    testFrameworks += new TestFramework("minitest.runner.Framework"),
    bloopExportJarClassifiers := Some(Set("sources"))
  )
)

name := "jsonrpc4s"
crossScalaVersions := List(scala212, scala213)
releaseEarlyWith := SonatypePublisher
publishTo := sonatypePublishToBundle.value
libraryDependencies ++= List(
  "io.monix" %% "monix" % "3.2.0",
  "com.outr" %% "scribe" % "3.5.5",
  "com.outr" %% "scribe-file" % "3.5.5" % Test,
  "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-core" % "2.13.5",
  "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-macros" % "2.13.5" % Provided,
  "io.monix" %% "minitest" % "2.9.6" % Test,
  "com.lihaoyi" %% "pprint" % "0.6.6" % Test
)
