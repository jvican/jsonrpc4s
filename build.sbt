val repoPattern = Patterns(
  Vector("[orgPath]/[module]/[revision]/[module]-[revision]-ivy.[ext]"),
  Vector("[orgPath]/[module]/[revision]/[artifact]-[revision](-[classifier]).[ext]"),
  isMavenCompatible = false,
  descriptorOptional = false,
  skipConsistencyCheck = true
)

val artifactoryUser = sys.env.get("NETFLIX_ARTIFACTORY_USER").getOrElse("")
val artifactoryPassword = sys.env.get("NETFLIX_ARTIFACTORY_PASSWORD").getOrElse("")
val publishSettings = List(
  credentials += Credentials("Artifactory Realm", "artifacts.netflix.com", artifactoryUser, artifactoryPassword),
  publishTo := Option(
    if (isSnapshot.value) Resolver.url("Netflix Snapshot Local", url("https://artifacts.netflix.com/libs-snapshots-local"))(repoPattern)
    else Resolver.url("Netflix Release Local", url("https://artifacts.netflix.com/libs-releases-local"))(repoPattern)
  )
)

inThisBuild(
  List(
    organization := "me.vican.jorge",
    homepage := Some(url("https://github.com/jvican/json4pc4")),
    publishMavenStyle := false,
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
    bloopExportJarClassifiers := Some(Set("sources")),
    releaseEarlyWith := SonatypePublisher
  ) ++ publishSettings
)

name := "jsonrpc4s"
publishTo := sonatypePublishToBundle.value
libraryDependencies ++= List(
  "io.monix" %% "monix" % "3.1.0",
  "com.outr" %% "scribe" % "2.7.10",
  "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-core" % "2.0.4",
  "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-macros" % "2.0.4" % Provided,
  "io.monix" %% "minitest" % "2.7.0" % Test,
  "com.lihaoyi" %% "pprint" % "0.5.6" % Test
)
publishSettings
