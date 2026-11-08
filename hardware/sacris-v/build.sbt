// SaCriS-V is licensed under the BSD 3-Clause License; see LICENSE.
// Redistributed third-party components keep their own licenses; see NOTICE.

ThisBuild / scalaVersion     := "2.13.15"
ThisBuild / version          := "0.1.0"
ThisBuild / organization     := "de.hu-berlin.informatik"

val chiselVersion = "6.6.0"

lazy val hardfloat = (project in file("dependencies/hardfloat/hardfloat"))
  .settings(
    name := "hardfloat",
    libraryDependencies += "org.chipsalliance" %% "chisel" % chiselVersion,
    addCompilerPlugin("org.chipsalliance" % "chisel-plugin" % chiselVersion cross CrossVersion.full),
    scalacOptions ++= Seq(
      "-language:reflectiveCalls",
      "-deprecation",
      "-feature",
      "-Xcheckinit",
      "-Ymacro-annotations",
    ),
  )

lazy val root = (project in file("."))
  .dependsOn(hardfloat)
  .settings(
    name := "sacris-v",
    libraryDependencies ++= Seq(
      "org.chipsalliance" %% "chisel" % chiselVersion,
    ),
    scalacOptions ++= Seq(
      "-language:reflectiveCalls",
      "-deprecation",
      "-feature",
      "-Xcheckinit",
      "-Ymacro-annotations",
    ),
    addCompilerPlugin("org.chipsalliance" % "chisel-plugin" % chiselVersion cross CrossVersion.full),
    Compile / mainClass := Some("sacris_v.TopLevel"),
  )
