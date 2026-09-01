package com.mchange.orvdo.exception

// `zio.Duration` is a bare alias for `java.time.Duration`, so it carries no
// methods of its own; `render` lives on `DurationOps`, reachable only through
// this implicit conversion from the `zio` package. Importing the type alone
// gets you a "value render is not a member of java.time.Duration" that says
// nothing about the missing conversion. Named narrowly rather than `zio.*` to
// keep the whole ZIO namespace out of this file.
import zio.duration2DurationOps

class OrvdoException(message : String, cause : Throwable = null) extends Exception(message, cause)
final case class AwaitVideoTimeout(jobId : String, timeout : zio.Duration) extends OrvdoException(s"Job $jobId did not finish within ${timeout.render}")
final case class TargetExists(path: os.Path) extends OrvdoException(s"$path already exists; pass --force to overwrite it")
final case class ApiError(status: Int, body: String) extends OrvdoException(s"OpenRouter returned HTTP $status: ${body.take(500)}")
