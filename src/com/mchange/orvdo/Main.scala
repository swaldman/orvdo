package com.mchange.orvdo

import cats.data.Validated
import cats.syntax.all.*
import com.monovore.decline.{Argument, Command, Opts}
import exception.*
import java.security.MessageDigest
import java.time.{Instant, ZoneOffset}
import java.time.format.DateTimeFormatter
import zio.*

/** Whether to write a receipt, and where. Three states rather than an
  * `Option[Option[os.Path]]`, because "no receipt" and "a receipt somewhere I
  * did not name" are different requests and should read as different. */
enum ReceiptTo:
  case No
  case Derived
  case At(path: os.Path)

/** The output-side options, which `submit` and `check` share entirely. Bundled
  * so the function that acts on them takes four arguments rather than eight. */
final case class Output(
    download: DownloadTo,
    force: Boolean,
    json: Boolean,
    receipt: ReceiptTo
)

/** Whether to save the video, and under whose choice of name.
  *
  * `Auto` exists because `--download-as` is the wrong default for repeated
  * use: re-running a command out of shell history aims at a name that is now
  * occupied, and the no-clobber diversion — a safety net — fires as a matter of
  * routine. A name derived from the job cannot collide with the previous run of
  * a *different* job, which is the common case.
  */
enum DownloadTo:
  case No
  case Auto
  case At(path: os.Path)

/** Where a save may go without destroying anything.
  *
  * Refusing outright was the old behaviour and it reads, to someone who has
  * just paid for a render, like the render is gone. Diverting keeps the bytes
  * and still reports failure, which is the honest combination: the file is
  * safe, and the command did not do what was asked.
  */
enum Target:
  case Requested(path: os.Path)
  case Diverted(path: os.Path, requested: os.Path)
  case Blocked(requested: os.Path, fallback: os.Path)

/** What a receipt records beyond the job itself. Only the `submit` path knows
  * either: `check` is handed a job id and nothing about how it was asked for. */
final case class Provenance(
    model: Option[VideoModel] = None,
    prompt: Option[String] = None,
    /** The JSON actually submitted, so the receipt can record what we asked
      * for and not merely what came back. Only `submit` has it: `check` is
      * handed a job id and knows nothing about how it was requested. */
    body: Option[ujson.Value] = None
)

enum Cmd:
  case ListModels(apiKey: String, filter: Option[String])
  case Check(
      apiKey: String,
      jobId: String,
      await: Boolean,
      download: DownloadTo,
      force: Boolean,
      json: Boolean,
      receipt: ReceiptTo
  )
  case Download(apiKey: String, url: String, target: os.Path, force: Boolean)
  case Submit(
      apiKey: String,
      model: String,
      duration: Option[Int],
      resolution: Option[String],
      aspectRatio: Option[String],
      generateAudio: Option[Boolean],
      promptFile: os.Path,
      firstFrame: Option[ImageUrl],
      lastFrame: Option[ImageUrl],
      references: List[ImageUrl],
      await: Boolean,
      download: DownloadTo,
      force: Boolean,
      json: Boolean,
      receipt: ReceiptTo,
      params: List[Param]
  )

object Cli:

  /** Relative paths resolve against the working directory; absolute ones are
    * taken as given. That is exactly `os.Path(s, os.pwd)`. */
  given Argument[os.Path] = Argument.from("path") { s =>
    Validated
      .catchNonFatal(os.Path(s, os.pwd))
      .leftMap(e => s"invalid path: ${e.getMessage}")
      .toValidatedNel
  }

  /** OpenRouter fetches these itself, so it needs somewhere it can reach. The
    * natural mistake is a local path, which would otherwise travel all the way
    * to the API before failing. */
  given Argument[ImageUrl] = Argument.from("url") { s =>
    if s.startsWith("http://") || s.startsWith("https://") || s.startsWith("data:") then
      Validated.valid(ImageUrl(s))
    else
      Validated.invalidNel(
        s"images are passed to OpenRouter by URL for it to fetch, and '$s' is not one; " +
          "a local file has to be hosted somewhere reachable first"
      )
  }

  private val apiKey: Opts[String] =
    Opts.env[String](
      "OPENROUTER_API_KEY",
      help = "Your OpenRouter API key. Create one at https://openrouter.ai/keys."
    )

  /** `Option[Boolean]`, not `orFalse`: the difference between "the user asked
    * for silence" and "the user said nothing" is exactly what decides whether
    * we warn. Without the `--no-` form there would be no way to ask for a
    * silent clip on purpose, and the warning would nag forever. */
  private val generateAudio: Opts[Option[Boolean]] =
    Opts
      .flag(
        "generate-audio",
        "Generate synchronised audio. On most models this roughly doubles the per-second price."
      )
      .map(_ => true)
      .orElse(
        Opts
          .flag("no-generate-audio", "Generate silent video, without warning about the default.")
          .map(_ => false)
      )
      .orNone

  private val params: Opts[List[Param]] =
    Opts
      .options[String](
        "param",
        "Model-specific passthrough parameter as key=value; repeatable. The value is " +
          "read as JSON when it parses and as a string otherwise. See the " +
          "`passthrough` row of `list-models` for what a model accepts.",
        short = "P",
        metavar = "key=value"
      )
      .orEmpty
      .mapValidated(_.traverse(s => Param.parse(s).toValidatedNel))

  /** Shared by `submit` and `check`; the help differs because only `submit` has
    * an `--await` to depend on. */
  /** `--download` names the file for you; `--download-as` lets you name it.
    * Three states rather than a flag plus an option, so "not asked for" stays
    * distinguishable from "asked for, at a name of your choosing". */
  private def downloadTo(flagHelp: String, pathHelp: String): Opts[DownloadTo] =
    (
      Opts.flag("download", flagHelp).orFalse,
      Opts.option[os.Path]("download-as", pathHelp, metavar = "path").orNone
    ).mapN {
      case (_, Some(path)) => DownloadTo.At(path)
      case (true, None)    => DownloadTo.Auto
      case (false, None)   => DownloadTo.No
    }

  private val await: Opts[Boolean] =
    Opts
      .flag("await", "Poll until the job reaches a terminal state before printing the result.")
      .orFalse

  private val force: Opts[Boolean] =
    Opts.flag("force", "Overwrite an existing target instead of saving beside it.").orFalse

  /** `--receipt` alone derives a path; `--receipt-as` names one and implies the
    * former, so there is no way to ask for a receipt location without a
    * receipt. */
  private val receipt: Opts[ReceiptTo] =
    (
      Opts
        .flag("receipt", "Write a receipt recording how this video was made.")
        .orFalse,
      Opts
        .option[os.Path](
          "receipt-as",
          "Write the receipt to this path. Implies --receipt.",
          metavar = "path"
        )
        .orNone
    ).mapN {
      case (_, Some(path)) => ReceiptTo.At(path)
      case (true, None)    => ReceiptTo.Derived
      case (false, None)   => ReceiptTo.No
    }

  /** `download` attaches the API key to whatever URL it is handed, so it will
    * only do so to OpenRouter and only over TLS. A mistyped host — or one
    * pasted from somewhere untrustworthy — would otherwise be given a working
    * credential, which is a poor trade for the convenience of not typing curl. */
  private def foreignHost(url: String): Option[String] =
    scala.util.Try(java.net.URI(url)).toOption match
      case None => Some(s"not a URL: $url")
      case Some(u) =>
        val scheme = Option(u.getScheme).getOrElse("")
        val host = Option(u.getHost).getOrElse("")
        if scheme != "https" then
          Some(
            s"refusing to send your API key over ${if scheme.isEmpty then "a URL with no scheme" else scheme}: $url"
          )
        else if host != "openrouter.ai" && !host.endsWith(".openrouter.ai") then
          Some(s"refusing to send your API key to '$host'; download only fetches from openrouter.ai")
        else None

  private val downloadOpts: Opts[Cmd.Download] =
    (
      apiKey,
      Opts.option[String](
        "url",
        "Content URL to fetch, as printed in a job record's Video row.",
        short = "u",
        metavar = "url"
      ),
      // `--as` is the short form; `--download-as` is kept for consistency with
      // the other subcommands. `orElse` makes exactly one of them required and
      // renders them as alternatives in the usage line.
      Opts
        .option[os.Path]("as", "Write the fetched bytes to this path.", metavar = "path")
        .orElse(
          Opts.option[os.Path](
            "download-as",
            "Synonym for --as, spelled as the other subcommands spell it.",
            metavar = "path"
          )
        ),
      force
    ).mapN(Cmd.Download.apply)

  private val download: Opts[Cmd] =
    Opts.subcommand(
      "download",
      "Fetch a content URL from a job record. Content URLs are unsigned but still " +
        "require your API key, so a browser or a bare curl will not do."
    )(
      downloadOpts.mapValidated { d =>
        foreignHost(d.url).fold(Validated.valid(d))(Validated.invalidNel)
      }
    )

  private val json: Opts[Boolean] =
    Opts
      .flag(
        "json",
        "Print the raw JSON response instead of the formatted rows."
      )
      .orFalse

  private val listModels: Opts[Cmd] =
    Opts.subcommand(
      "list-models",
      "List the available video generation models with their supported parameters and pricing."
    )(
      (
        apiKey,
        Opts
          .option[String](
            "filter",
            "Show only models whose id or name contains this text, case-insensitively.",
            short = "f",
            metavar = "text"
          )
          .orNone
      ).mapN(Cmd.ListModels.apply)
    )

  /** Typed `Opts[Cmd.Check]` for the same reason as `submitOpts`: inlined, the
    * subcommand's expected type would erase the fields `mapValidated` needs. */
  private val checkOpts: Opts[Cmd.Check] =
    (
      apiKey,
      Opts.option[String]("job-id", "The job id returned by `submit`.", metavar = "id"),
      await,
      downloadTo(
        "Save this job's videos, naming them for the job.",
        "Write this job's video to this path."
      ),
      force,
      json,
      receipt
    ).mapN(Cmd.Check.apply)

  private val check: Opts[Cmd] =
    Opts.subcommand("check", "Fetch the latest state of a previously submitted job.")(
      checkOpts.mapValidated { c =>
        if c.force && c.download == DownloadTo.No then
          Validated.invalidNel("--force only means something alongside --download or --download-as.")
        else Validated.valid(c)
      }
    )

  /** Typed as `Opts[Cmd.Submit]` deliberately: if this were inlined into the
    * `Opts.subcommand[Cmd]` below, the expected type would push `Cmd` into the
    * `mapN` and `mapValidated` would only see the enum's common interface. */
  private val submitOpts: Opts[Cmd.Submit] =
    (
      apiKey,
      Opts.option[String](
        "model",
        "Model slug, e.g. google/veo-3.1. See `list-models`.",
        short = "m",
        metavar = "slug"
      ),
      Opts
        .option[Int](
          "duration",
          "Clip length in seconds; must be one of the model's supported durations.",
          short = "d",
          metavar = "seconds"
        )
        .orNone,
      Opts
        .option[String](
          "resolution",
          "Frame height, e.g. 720p. Must be one of the model's supported resolutions.",
          short = "r",
          metavar = "res"
        )
        .orNone,
      Opts
        .option[String](
          "aspect-ratio",
          "Frame shape, e.g. 16:9. Must be one of the model's supported ratios.",
          short = "a",
          metavar = "w:h"
        )
        .orNone,
      generateAudio,
      Opts.option[os.Path](
        "prompt-file",
        "Plain-text file whose contents are used as the prompt.",
        short = "p"
      ),
      Opts
        .option[ImageUrl](
          "first-frame",
          "Image to open the clip on. The model must list first_frame under `frames`."
        )
        .orNone,
      Opts
        .option[ImageUrl](
          "last-frame",
          "Image to close the clip on. The model must list last_frame under `frames`."
        )
        .orNone,
      Opts
        .options[ImageUrl](
          "reference",
          "Style or subject reference image; repeatable. Guidance rather than an exact frame."
        )
        .orEmpty,
      await,
      downloadTo(
        "Save the finished videos, naming them for the job. Requires --await.",
        "Write the finished video to this path. Requires --await."
      ),
      force,
      json,
      receipt,
      params
    ).mapN(Cmd.Submit.apply)

  private val submit: Opts[Cmd] =
    Opts.subcommand("submit", "Submit a text-to-video generation job.")(
      submitOpts.mapValidated { s =>
        if s.download != DownloadTo.No && !s.await then
          Validated.invalidNel(
            "--download and --download-as require --await; there is no video to save until the job finishes."
          )
        else if s.force && s.download == DownloadTo.No then
          Validated.invalidNel("--force only means something alongside --download or --download-as.")
        else Validated.valid(s)
      }
    )

  val command: Command[Cmd] =
    Command(
      name = "orvdo",
      header =
        """Generate videos with OpenRouter's asynchronous video API.
          |
          |Generation is a job: `submit` returns immediately with a job id, `check`
          |reports the current state, and a completed job exposes a URL to download from.""".stripMargin
    )(listModels.orElse(submit).orElse(check).orElse(download))

object Main extends ZIOAppDefault:

  private def runCmd(cmd: Cmd): Task[Unit] = cmd match

    case Cmd.ListModels(key, filter) =>
      loadCatalog(key)
        .map(ms => filter.fold(ms)(f => ms.filter(_.matches(f))))
        .flatMap(ms => Console.printLine(Render.models(ms, filter)))

    case d: Cmd.Download =>
      // The same no-clobber policy as a job download, so the two behave alike.
      // There is no job in hand here, so the annotation comes from the URL.
      def save(path: os.Path) =
        OpenRouter.download(d.apiKey, d.url, path, force = true) *>
          Console.printLine(Render.saved(path))
      freeTarget(d.target, urlTag(d.url), d.force) match
        case Target.Requested(path) => save(path)
        case Target.Diverted(path, requested) =>
          save(path) *> ZIO.fail(SavedElsewhere("the download", requested, path))
        case Target.Blocked(requested, fallback) =>
          ZIO.fail(NotSaved("the download", requested, fallback, Some(d.url)))

    case c: Cmd.Check =>
      for
        job <- OpenRouter.rawCheck(c.apiKey, c.jobId)
        // A job that has already finished needs no waiting, and saying we are
        // about to wait for it would be a small lie.
        finished <-
          if c.await && !job.value.isTerminal then
            awaitTerminal(c.apiKey, job, s"job ${job.value.id} is ${job.value.status}, waiting...")
          else ZIO.succeed(job)
        // `check` knows the job and nothing about how it was asked for, so a
        // receipt written here has no model block and no prompt.
        _ <- reportAndDownload(
          c.apiKey,
          Output(c.download, c.force, c.json, c.receipt),
          finished,
          Provenance()
        )
      yield ()

    case s: Cmd.Submit =>
      for
        prompt <- readPrompt(s.promptFile)
        model <- findModel(s.apiKey, s.model)
        // Shadowing is deliberate: nothing below can reach the un-canonicalised
        // settings by accident.
        s <- canonical(s, model)
        (request, chosen) = cheapest(s, prompt, model)
        _ <- ZIO.when(chosen.nonEmpty)(progress(Render.defaults(s.model, chosen)))
        provider <- passthroughFor(s, model)
        job <- OpenRouter.rawSubmit(s.apiKey, request, provider)
        provenance = Provenance(Some(model), Some(prompt), Some(VideoRequest.body(request, provider)))
        out = Output(s.download, s.force, s.json, s.receipt)
        _ <-
          // Without --await there is nothing to download, so this reduces to
          // printing the record and writing the receipt. Worth doing anyway:
          // the model and prompt are known only here, and a later `check`
          // could never recover them.
          if !s.await then reportAndDownload(s.apiKey, out, job, provenance)
          else awaitAndMaybeDownload(s, out, job, provenance)
      yield ()

  /** `--json` prints the bytes OpenRouter actually sent. It deliberately does
    * not write the parsed `VideoJob` back out: that would reproduce exactly the
    * blind spot the flag exists to see past. */
  private def renderJob(r: Raw[VideoJob], json: Boolean): String =
    if json then ujson.write(r.json, indent = 2) else Render.job(r.value)

  /** Read the catalog, reporting anything in it we do not model, at both
    * levels: the envelope and each entry. Every path that reads the catalog
    * goes through here, so the warning cannot depend on which subcommand
    * happened to fetch it. */
  private def loadCatalog(apiKey: String): Task[List[VideoModel]] =
    for
      raw <- OpenRouter.rawListModels(apiKey)
      _ <- warnUnmodeled("VideoModels", Wire.unmodeled(raw.json, VideoModels.knownKeys))
      _ <- warnUnmodeled(
        "VideoModel",
        Wire.unmodeledAcross(Wire.arrayAt(raw.json, "data"), VideoModel.knownKeys)
      )
    yield raw.value.data

  private def warnUnmodeled(scope: String, keys: List[String]): UIO[Unit] =
    ZIO.when(keys.nonEmpty)(progress(Render.unmodeled(scope, keys))).unit

  /** Print a job record, warning first about anything in the response we did
    * not model. Under `--json` the raw response is printed instead, which shows
    * those fields but does not name them — so the warning earns its place in
    * both modes. */
  private def printJob(r: Raw[VideoJob], json: Boolean): Task[Unit] =
    warnUnmodeled("VideoJob", Wire.unmodeled(r.json, VideoJob.knownKeys)) *>
      Console.printLine(renderJob(r, json))

  /** `submit` has to know what the model supports before it can pick the cheap
    * option, so it now costs a `list-models` call first. Failing when the slug
    * is absent from the catalog is deliberate: without the catalog we cannot
    * honour the cost-safety promise, and an unknown slug is far more often a
    * typo than a model OpenRouter serves but does not list. */
  private def findModel(apiKey: String, slug: String): Task[VideoModel] =
    loadCatalog(apiKey)
      .flatMap { ms =>
        ZIO
          .fromOption(ms.find(_.id == slug))
          .orElseFail(
            new IllegalArgumentException(
              s"no such model: $slug — try `list-models --filter ${slug.takeWhile(_ != '/')}`"
            )
          )
      }

  /** Check the supplied quality settings against the catalog and answer with
    * the catalog's own spelling, so `-r 4k` goes on the wire as `4K`.
    *
    * Rejecting here rather than letting OpenRouter answer 400 is worth the
    * lines because the reply names what the model does take. A dimension the
    * catalog lists nothing for is passed through untouched: an empty list means
    * the catalog has nothing to say, not that nothing is allowed. All the
    * problems are reported at once rather than one per run. */
  private[orvdo] def canonical(s: Cmd.Submit, m: VideoModel): Task[Cmd.Submit] =
    def matched[A](flag: String, supplied: Option[A], offered: List[A])(
        eq: (A, A) => Boolean
    ): Either[String, Option[A]] =
      supplied match
        case Some(v) if offered.nonEmpty =>
          offered
            .find(eq(_, v))
            .toRight(s"  $flag $v is not supported by ${s.model}; it offers ${offered.mkString(", ")}")
            .map(Some(_))
        case other => Right(other)

    /** Frame images are gated by a list of *frame types*, not of values, so
      * this asks a different question from `matched` even though it reports the
      * same way. As with the others, a catalog that lists nothing is treated as
      * having no opinion rather than as a refusal. */
    def frameAllowed(flag: String, supplied: Option[ImageUrl], frameType: String): Option[String] =
      val offered = m.supported_frame_images.getOrElse(Nil)
      supplied
        .filter(_ => offered.nonEmpty && !offered.contains(frameType))
        .map(_ =>
          s"  $flag is not supported by ${s.model}; it accepts ${offered.mkString(", ")}"
        )

    val duration = matched("--duration", s.duration, m.supported_durations.getOrElse(Nil))(_ == _)
    val resolution =
      matched("--resolution", s.resolution, m.supported_resolutions.getOrElse(Nil))(_.equalsIgnoreCase(_))
    val ratio =
      matched("--aspect-ratio", s.aspectRatio, m.supported_aspect_ratios.getOrElse(Nil))(_.equalsIgnoreCase(_))

    val problems = List(duration, resolution, ratio).collect { case Left(e) => e } ++
      List(
        frameAllowed("--first-frame", s.firstFrame, "first_frame"),
        frameAllowed("--last-frame", s.lastFrame, "last_frame")
      ).flatten

    problems match
      case Nil =>
        ZIO.succeed(
          s.copy(
            duration = duration.getOrElse(None),
            resolution = resolution.getOrElse(None),
            aspectRatio = ratio.getOrElse(None)
          )
        )
      case _ =>
        ZIO.fail(new IllegalArgumentException(problems.mkString("unsupported settings:\n", "\n", "")))

  /** Build the `provider.options` block for any `--param`s, resolving the
    * provider slug rather than guessing it: OpenRouter forwards only the block
    * matching the provider it routes to and discards the rest silently, so a
    * guess that misses would look exactly like a parameter the model ignored.
    * Every tag the model routes to is keyed, which costs nothing and removes
    * the need to care which one wins. */
  private def passthroughFor(s: Cmd.Submit, m: VideoModel): Task[Option[ujson.Value]] =
    if s.params.isEmpty then ZIO.none
    else
      for
        tags <- OpenRouter.providerTags(s.apiKey, s.model)
        _ <- ZIO
          .fail(
            new RuntimeException(
              s"cannot forward --param: no provider slug found for ${s.model}, " +
                "so there is no key to file the options under"
            )
          )
          .when(tags.isEmpty)
        allowed = m.allowed_passthrough_parameters.getOrElse(Nil).toSet
        unlisted = s.params.map(_.key).filterNot(allowed)
        _ <- progress(Render.passthrough(tags, s.params, unlisted))
      yield Some(Passthrough.block(tags, s.params))

  /** Fill in the cost-sensitive fields the CLI cannot yet set, taking the
    * cheapest value the model offers, and report each one we picked. Anything
    * the user set explicitly is left alone and goes unreported — the warning is
    * about choices made on their behalf, not choices they made. */
  private[orvdo] def cheapest(s: Cmd.Submit, prompt: String, m: VideoModel): (VideoRequest, List[Chosen]) =
    val chosen = List.newBuilder[Chosen]

    def pick[A](supplied: Option[A], cheap: Option[A], field: String, offered: List[String])(
        show: A => String
    ): Option[A] =
      supplied.orElse {
        cheap.map { a =>
          chosen += Chosen(field, show(a), offered)
          a
        }
      }

    val duration = pick(s.duration, m.cheapestDuration, "duration", m.supported_durations.getOrElse(Nil).map(_.toString))(d => s"$d s")
    val resolution =
      pick(s.resolution, m.cheapestResolution, "resolution", m.supported_resolutions.getOrElse(Nil))(identity)
    val ratio =
      pick(s.aspectRatio, m.cheapestAspectRatio, "ratio", m.supported_aspect_ratios.getOrElse(Nil))(identity)
    // An explicit --generate-audio is always forwarded; only the *default* is
    // withheld from models where audio is not a priced dimension, so we never
    // silently drop a flag the user actually typed.
    val audio = pick(s.generateAudio, Option.when(m.audioIsPriced)(false), "audio", List("on", "off"))(
      b => if b then "on" else "off"
    )

    val frames =
      s.firstFrame.map(ImagePart(_, Some("first_frame"))).toList ++
        s.lastFrame.map(ImagePart(_, Some("last_frame"))).toList

    val request = VideoRequest(
      model = s.model,
      prompt = prompt,
      frame_images = Option.when(frames.nonEmpty)(frames),
      input_references = Option.when(s.references.nonEmpty)(s.references.map(ImagePart(_))),
      duration = duration,
      resolution = resolution,
      aspect_ratio = ratio,
      generate_audio = audio
    )
    (request, chosen.result())

  private def readPrompt(path: os.Path): Task[String] =
    for
      exists <- ZIO.attemptBlocking(os.exists(path) && os.isFile(path))
      _ <- ZIO.fail(new IllegalArgumentException(s"no such prompt file: $path")).unless(exists)
      text <- ZIO.attemptBlocking(os.read(path)).map(_.trim)
      _ <- ZIO.fail(new IllegalArgumentException(s"prompt file is empty: $path")).when(text.isEmpty)
    yield text

  /** Poll to a terminal state, then attempt the download if one was asked for.
    * The full job information is printed either way, download or no download. */
  private def awaitAndMaybeDownload(
      s: Cmd.Submit,
      out: Output,
      job: Raw[VideoJob],
      provenance: Provenance
  ): Task[Unit] =
    for
      finished <- awaitTerminal(s.apiKey, job, s"submitted ${job.value.id}, waiting for completion...")
      _ <- reportAndDownload(s.apiKey, out, finished, provenance)
    yield ()

  /** Poll to a terminal state, narrating each status on stderr. Shared so that
    * `check --await` is the same wait `submit --await` performs, for a user
    * picking up the `--await` they did not ask for the first time. */
  private def awaitTerminal(apiKey: String, job: Raw[VideoJob], opening: String): Task[Raw[VideoJob]] =
    progress(opening) *> OpenRouter.rawAwaitCompletion(apiKey, job)(j => progress(s"  ${j.status}").ignore)

  /** Print the job record, then save if asked, then re-raise a download failure.
    *
    * That order is a hard requirement, not an accident of the for-comprehension:
    * the user must always get the content URL, even when the save failed, so
    * that a failed write is recoverable rather than a lost job. Shared by
    * `submit --await` and `check`, which differ only in how they got the job. */
  private def reportAndDownload(
      apiKey: String,
      out: Output,
      job: Raw[VideoJob],
      provenance: Provenance
  ): Task[Unit] =
    for
      attempted <- downloadFor(apiKey, out.download, out.force, job.value).either
      saved = attempted.toOption.getOrElse(Nil)
      _ <- printJob(job, out.json)
      _ <- ZIO.foreachDiscard(saved)((path, _) => Console.printLine(Render.saved(path)))
      // `--download-as` names one file and so takes only the first output;
      // `--download` names them all and takes them all, so the warning would
      // be false there. Stderr, since the record on stdout lists every URL.
      _ <- progress(Render.unsaved(job.value, saved.head._1))
        .when(out.download.isInstanceOf[DownloadTo.At] && saved.nonEmpty &&
              job.value.contentUrls.sizeIs > 1)
        .unit
      // A receipt is written whether or not the download worked, and records
      // the digest only when there is a file to digest. Its own failure is
      // captured for the same reason the download's is: the record has been
      // printed by now and must not be retracted.
      wrote <- writeReceipt(out, job.value, saved.headOption.map(_._1), provenance).either
      _ <- wrote match
        case Right(Some((path, _))) => Console.printLine(Render.receiptAt(path))
        case _                      => ZIO.unit
      _ <- ZIO.fromEither(attempted).unit
      _ <- ZIO.fromEither(wrote).unit
      // Everything landed, but possibly not where it was asked to. That is
      // still a failure, reported after the record so nothing is retracted.
      _ <- saved.collectFirst { case (path, Some(requested)) =>
        SavedElsewhere("the video", requested, path)
      } match
        case Some(e) => ZIO.fail(e)
        case None    => ZIO.unit
      _ <- wrote.toOption.flatten match
        case Some((path, Some(requested))) => ZIO.fail(SavedElsewhere("the receipt", requested, path))
        case _                             => ZIO.unit
    yield ()

  private[orvdo] def writeReceipt(
      out: Output,
      job: VideoJob,
      downloaded: Option[os.Path],
      provenance: Provenance
  ): Task[Option[(os.Path, Option[os.Path])]] =
    if out.receipt == ReceiptTo.No then ZIO.none
    else
      for
        digest <- ZIO.foreach(downloaded)(p => sha256(p).map(p -> _))
        desired = receiptPath(out, downloaded, job, provenance.model.map(_.id).orElse(job.model))
        // Receipts used to be written with `os.write.over`, on the reasoning
        // that a derived artefact may be regenerated. But a receipt records a
        // render that cost money and may name a file the new one does not, so
        // it gets the same protection as the video.
        path <- freeTarget(desired, job.id, out.force) match
          case Target.Requested(p)   => ZIO.succeed(p -> None)
          case Target.Diverted(p, r) => ZIO.succeed(p -> Some(r))
          case Target.Blocked(r, f)  => ZIO.fail(NotSaved("the receipt", r, f, None))
        _ <- ZIO.attemptBlocking {
          os.makeDir.all(path._1 / os.up)
          os.write.over(
            path._1,
            Render.receipt(provenance.model, job, digest, provenance.prompt, provenance.body) + "\n"
          )
        }
      yield Some(path)

  /** Beside the video when there is one, so the pair travels together; named
    * for the model and job otherwise, so it is identifiable alone. A model id
    * carries a slash, which would read as a directory, hence the substitution.
    */
  /** `clip.mp4` becomes `clip_<jobId>.mp4`, and an extensionless `clip`
    * becomes `clip_<jobId>`. Annotated rather than numbered, so the filename
    * says which render it holds. Job ids are opaque, so anything that is not
    * filename-safe is replaced rather than trusted. */
  /** The job id inside an OpenRouter content URL, for annotating a fallback
    * name when `download` is handed a path that is taken. A timestamp stands in
    * when the URL is not of that shape: the annotation only has to be stable
    * and distinct, and guessing a job id would be worse than not having one. */
  private[orvdo] def urlTag(url: String): String =
    """/videos/([^/?#]+)/content""".r.findFirstMatchIn(url).map(_.group(1)).getOrElse(timestamp())

  private[orvdo] def annotated(path: os.Path, jobId: String): os.Path =
    val safe = jobId.map(c => if c.isLetterOrDigit || "-_.".contains(c) then c else '-')
    val suffix = if path.ext.isEmpty then "" else "." + path.ext
    path / os.up / s"${path.baseName}_$safe$suffix"

  /** Decide where a save can land. `--force` means the caller has already
    * accepted the loss, so no diversion is attempted. */
  private[orvdo] def freeTarget(desired: os.Path, jobId: String, force: Boolean): Target =
    if force || !os.exists(desired) then Target.Requested(desired)
    else
      val fallback = annotated(desired, jobId)
      if os.exists(fallback) then Target.Blocked(desired, fallback)
      else Target.Diverted(fallback, desired)

  private[orvdo] def receiptPath(
      out: Output,
      savedTo: Option[os.Path],
      job: VideoJob,
      modelId: Option[String]
  ): os.Path =
    out.receipt match
      case ReceiptTo.At(path) => path
      case _ =>
        // Follows the file that actually landed, so a diverted or auto-named
        // video takes its receipt with it: `video_JOB.mp4.receipt`.
        savedTo match
          case Some(video) => video / os.up / s"${video.last}.receipt"
          case None =>
            val slug = modelId.getOrElse("video").replace('/', '-')
            os.pwd / s"$slug-${job.id}-${timestamp()}.receipt"

  private def timestamp(): String =
    DateTimeFormatter
      .ofPattern("yyyyMMdd'T'HHmmss'Z'")
      .withZone(ZoneOffset.UTC)
      .format(Instant.now)

  /** Streamed rather than read whole: a 4K clip runs to hundreds of megabytes,
    * and the digest is the one part of a receipt that should not itself be a
    * reason to run out of memory. */
  private[orvdo] def sha256(path: os.Path): Task[String] =
    ZIO.attemptBlocking {
      val digest = MessageDigest.getInstance("SHA-256")
      val buffer = new Array[Byte](64 * 1024)
      val in = os.read.inputStream(path)
      try
        var n = in.read(buffer)
        while n >= 0 do
          if n > 0 then digest.update(buffer, 0, n)
          n = in.read(buffer)
      finally in.close()
      digest.digest().map(b => f"${b & 0xff}%02x").mkString
    }

  /** Save the video, diverting rather than refusing when the name is taken.
    *
    * Returns the path written and, when it is not the path asked for, the one
    * that was. The caller reports that as a failure — the bytes are safe, but
    * the command did not do what it was told. */
  /** The name `--download` gives a video: `video_<jobId>.<ext>`, or
    * `video_<jobId>_<n>.<ext>` when the job produced several.
    *
    * The extension comes from the media type the server declared, which is the
    * only source available — the content endpoint sends no
    * `Content-Disposition`. The job id makes the name unique per render, which
    * is the whole point: re-running a command out of shell history aims at a
    * name that belongs to *this* job, not the last one. */
  private[orvdo] def autoName(job: VideoJob, index: Int, of: Int, extension: String): String =
    val safe = job.id.map(c => if c.isLetterOrDigit || "-_.".contains(c) then c else '-')
    val ordinal = if of > 1 then s"_$index" else ""
    s"video_$safe$ordinal.$extension"

  /** Save what the job produced.
    *
    * `--download-as` names one file and so takes only the first output, which
    * is why the multi-video warning exists. `--download` names them all, so it
    * takes them all — the gap that warning describes does not arise.
    */
  private def downloadFor(
      apiKey: String,
      to: DownloadTo,
      force: Boolean,
      job: VideoJob
  ): Task[List[(os.Path, Option[os.Path])]] =
    def save(url: String, desired: os.Path, what: String): Task[(os.Path, Option[os.Path])] =
      freeTarget(desired, job.id, force) match
        // force = true below even when the caller did not ask: the path has
        // just been established free, and `download`'s guard would re-check it.
        case Target.Requested(path) =>
          OpenRouter.download(apiKey, url, path, force = true).map(_ -> None)
        case Target.Diverted(path, requested) =>
          OpenRouter.download(apiKey, url, path, force = true).map(_ -> Some(requested))
        case Target.Blocked(requested, fallback) =>
          ZIO.fail(NotSaved(what, requested, fallback, Some(url)))

    def noContent = ZIO.fail(
      new RuntimeException(
        s"nothing to download: job ${job.id} has status '${job.status}' and no content URL"
      )
    )

    to match
      case DownloadTo.No => ZIO.succeed(Nil)

      case DownloadTo.At(desired) =>
        job.firstContentUrl match
          case Some(url) => save(url, desired, "the video").map(List(_))
          case None      => noContent

      case DownloadTo.Auto =>
        val urls = job.contentUrls
        if urls.isEmpty then noContent
        else
          // Fetched before named, because the name depends on the media type.
          ZIO.foreach(urls.zipWithIndex) { (url, i) =>
            for
              fetched <- OpenRouter.fetch(apiKey, url)
              desired = os.pwd / autoName(job, i, urls.size, fetched.extension)
              path <- freeTarget(desired, timestamp(), force) match
                case Target.Requested(p)   => writeBytes(p, fetched.bytes).as(p -> None)
                case Target.Diverted(p, r) => writeBytes(p, fetched.bytes).as(p -> Some(r))
                case Target.Blocked(r, f)  => ZIO.fail(NotSaved("the video", r, f, Some(url)))
            yield path
          }

  private def writeBytes(path: os.Path, bytes: Array[Byte]): Task[Unit] =
    ZIO.attemptBlocking {
      os.makeDir.all(path / os.up)
      os.write.over(path, bytes)
    }

  /** A timeout is the most recoverable failure this tool produces: the job is
    * almost certainly still rendering, OpenRouter still has it, and the id is
    * right there in the exception. Saying only that we gave up waiting would
    * leave the user to work that out. Every other failure gets the plain line. */
  private def reportFailure(e: Throwable): UIO[ExitCode] =
    val lines = e match
      case t: AwaitVideoTimeout =>
        List(
          s"error: ${t.getMessage}",
          "       the job is probably still rendering; pick it up again with",
          s"       orvdo check --job-id ${t.jobId} --await --download-as <path>"
        )
      case d: SavedElsewhere =>
        // Loud on purpose. The failure exit is honest -- we did not write where
        // we were told -- but a bare "error:" line invites the reader to think
        // a render they paid for is gone, when it is on disk one line down.
        List(
          s"error: ${d.requested}",
          "       already exists, so it was NOT overwritten.",
          s"       ${d.what.capitalize} was saved instead as:",
          s"           ${d.actual}",
          "       Nothing has been lost. Pass --force to overwrite the original name."
        )

      case n: NotSaved =>
        List(
          s"error: ${n.requested}",
          s"       and ${n.fallback}",
          s"       both exist, so ${n.what} was not written."
        ) ++ n.url.fold(Nil)(u =>
          List(
            "       The content is still on OpenRouter, and is not lost. Fetch it with:",
            "           orvdo download --url \"" + u + "\" --as <a free path>",
            "       or re-run with --force to overwrite."
          )
        )

      case other => List(s"error: ${other.getMessage}")
    ZIO.foreachDiscard(lines)(Console.printLineError(_).ignore).as(ExitCode.failure)

  private def progress(line: String): UIO[Unit] =
    Console.printLineError(line).ignore

  override def run: ZIO[ZIOAppArgs & Scope, Any, Any] =
    for
      args <- getArgs
      code <- Cli.command.parse(args, sys.env) match
        case Left(help) if help.errors.isEmpty =>
          // --help and friends: not a failure.
          Console.printLine(help.toString).ignore.as(ExitCode.success)
        case Left(help) =>
          Console.printLineError(help.toString).ignore.as(ExitCode.failure)
        case Right(cmd) =>
          runCmd(cmd).as(ExitCode.success).catchAll(reportFailure)
      // ZIO derives the process exit code from whether `run` *failed*, not from
      // the value it returns, so a returned ExitCode is otherwise silently
      // dropped and every error path exits 0. Failing instead would set the
      // code but make the runtime log a Cause stack trace over the one-line
      // message above. `exit` claims the shutting-down flag itself, which turns
      // the runtime's own shutdown hook into a no-op, so this cannot deadlock.
      _ <- exit(code).unless(code == ExitCode.success)
    yield code
