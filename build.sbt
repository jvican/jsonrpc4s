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
    scalaVersion := "2.13.1",
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
releaseEarlyWith := SonatypePublisher
publishTo := sonatypePublishToBundle.value
libraryDependencies ++= List(
  "io.monix" %% "monix" % "3.1.0",
  "com.outr" %% "scribe" % "2.7.10",
  "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-core" % "2.1.15",
  "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-macros" % "2.1.15" % Provided,
  "io.monix" %% "minitest" % "2.7.0" % Test,
  "com.lihaoyi" %% "pprint" % "0.5.6" % Test
)
