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

/** The requested path was occupied, so the bytes went to a job-annotated
  * sibling instead. Nothing was lost — but the command did not do what it was
  * told, so it still fails. `what` is "the video", "the receipt" or similar. */
final case class SavedElsewhere(what: String, requested: os.Path, actual: os.Path)
    extends OrvdoException(s"$requested already exists, so $what was saved as $actual instead")

/** Both the requested path and every fallback tried were occupied, so nothing
  * was written at all. `fallback` is the last name attempted — for an
  * auto-named file that is the end of a numbered series, not a single
  * alternative, hence the wording. `url`, when present, is what will fetch the
  * content that never landed. */
final case class NotSaved(what: String, requested: os.Path, fallback: os.Path, url: Option[String])
    extends OrvdoException(s"$requested is taken, and so is $fallback, so $what was not written")
