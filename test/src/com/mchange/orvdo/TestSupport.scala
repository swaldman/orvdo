package com.mchange.orvdo

import com.monovore.decline.Help
import zio.*

/** Shared helpers. Nothing here talks to the network: every test in this suite
  * is hermetic, so `./mill test` needs no API key and no connection. Live
  * checks against OpenRouter stay manual — see CLAUDE.md. */
object TestSupport:

  private val runtime = Runtime.default

  /** Run a ZIO effect to a value, throwing on failure, for tests. */
  def run[A](z: Task[A]): A =
    Unsafe.unsafe(implicit u => runtime.unsafe.run(z).getOrThrowFiberFailure())

  def runEither[A](z: Task[A]): Either[Throwable, A] =
    Unsafe.unsafe(implicit u => runtime.unsafe.run(z.either).getOrThrowFiberFailure())

  val Key = "OPENROUTER_API_KEY"
  val Env = Map(Key -> "sk-or-test")

  /** Parse a command line the way `Main` does. */
  def parse(args: String*): Either[Help, Cmd] = Cli.command.parse(args, Env)

  /** The first error decline reports, or "" when the parse succeeded. */
  def errorOf(args: String*): String =
    parse(args*).fold(_.errors.headOption.getOrElse(""), _ => "")

  def submitOf(args: String*): Cmd.Submit =
    parse(args*) match
      case Right(s: Cmd.Submit) => s
      case Right(other)         => sys.error(s"expected a Submit, got $other")
      case Left(help)           => sys.error(s"expected a Submit, got errors: ${help.errors}")

  def checkOf(args: String*): Cmd.Check =
    parse(args*) match
      case Right(c: Cmd.Check) => c
      case Right(other)        => sys.error(s"expected a Check, got $other")
      case Left(help)          => sys.error(s"expected a Check, got errors: ${help.errors}")

  /** A model with everything, for the ranking and validation tests. */
  val veo = upickle.default.read[VideoModel](
    """{"id":"google/veo-3.1","name":"Google: Veo 3.1",
       |"canonical_slug":"google/veo-3.1-20260320",
       |"supported_durations":[4,6,8],
       |"supported_resolutions":["720p","1080p","4K"],
       |"supported_aspect_ratios":["16:9","9:16"],
       |"supported_frame_images":["first_frame","last_frame"],
       |"generate_audio":true,"seed":true,
       |"pricing_skus":{"duration_seconds_with_audio":"0.40",
       |                "duration_seconds_without_audio":"0.20"}}""".stripMargin)

  /** Lists only `first_frame`, and prices no audio: the awkward cases. */
  val gen45 = upickle.default.read[VideoModel](
    """{"id":"runway/gen-4.5","name":"Runway: Gen-4.5",
       |"supported_durations":[2,3,4],
       |"supported_resolutions":["720p"],
       |"supported_aspect_ratios":["16:9","9:16"],
       |"supported_frame_images":["first_frame"],
       |"pricing_skus":{"cents_per_second_output":"12"}}""".stripMargin)

  /** Lists nothing at all: the catalog has no opinion about anything. */
  val aleph = upickle.default.read[VideoModel](
    """{"id":"runway/aleph-2","name":"Runway: Aleph 2.0",
       |"allowed_passthrough_parameters":["contentModeration","keyframes"]}""".stripMargin)
