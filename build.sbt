import ReleaseTransformations._
import Dependencies._

name := "play-googleauth"

val bintrayReleaseSettings = Seq(

  organization := "com.ovoenergy",
  description := "Simple Google authentication module for Play 2 (Temporary OVO fork)",

  bintrayRepository := "maven",
  bintrayOrganization := Some("ovotech"),
  licenses := Seq("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0.html")),

  pomExtra := {
    <url>https://github.com/guardian/play-googleauth</url>
      <developers>
        <developer>
          <id>sihil</id>
          <name>Simon Hildrew</name>
          <url>https://github.com/sihil</url>
        </developer>
      </developers>
  },

  scmInfo := Some(ScmInfo(
    url("https://github.com/guardian/play-googleauth"),
    "scm:git:git@github.com:guardian/play-googleauth.git"
  )),

  releaseCrossBuild := true, // true if you cross-build the project for multiple Scala versions
  releaseProcess := Seq[ReleaseStep](
    checkSnapshotDependencies,
    inquireVersions,
    runClean,
    runTest,
    setReleaseVersion,
    commitReleaseVersion,
    tagRelease,
    publishArtifacts,
    setNextVersion,
    commitNextVersion,
    pushChanges
  )
)

def projectWithPlayVersion(majorMinorVersion: String) =
  Project(s"play-v$majorMinorVersion", file(s"play-v$majorMinorVersion")).settings(
    scalaVersion       := "2.12.10",

    scalacOptions ++= Seq("-feature", "-deprecation"),

    libraryDependencies ++= Seq(
      "com.gu.play-secret-rotation" %% "core" % "0.17",
      "org.typelevel" %% "cats-core" % "2.0.0",
      commonsCodec,
      "org.scalatest" %% "scalatest" % "3.0.8" % "test"
    ) ++ googleDirectoryAPI ++ playLibs(majorMinorVersion),

    bintrayReleaseSettings
  )

lazy val `play-v26` = projectWithPlayVersion("26")
lazy val `play-v27` = projectWithPlayVersion("27").settings(crossScalaVersions := Seq(scalaVersion.value, "2.13.1"))

lazy val `play-googleauth-root` = (project in file(".")).aggregate(
  `play-v26`,
  `play-v27`
).settings(
  publishArtifact := false,
  skip in publish := true,

  bintrayReleaseSettings
)
