organization := "io.kevinlee.akka"

name := "akka-example-app"

version := "1.0"

scalaVersion := "2.12.2"

lazy val akkaVersion = "2.4.20"
lazy val akkaHttpVesion = "10.0.11"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor" % akkaVersion,
  "com.typesafe.akka" %% "akka-http" % akkaHttpVesion,

  "com.typesafe.akka" %% "akka-testkit" % akkaVersion,
  "com.typesafe.akka" %% "akka-http-testkit" % akkaHttpVesion % Test,

  "org.scalatest" %% "scalatest" % "3.0.1" % Test
)
