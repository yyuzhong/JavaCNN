organization := "cloud.pvamu"

name := "MyCNN"

version := "1.0"

libraryDependencies ++= Seq()

crossPaths := false
autoScalaLibrary := false

javaSource in Compile := baseDirectory.value / "src"
