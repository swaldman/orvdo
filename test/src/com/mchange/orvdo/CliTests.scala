package com.mchange.orvdo

import utest.*
import TestSupport.*

/** The argument contract. Every case here was previously a line in an ad-hoc
  * shell sweep that could only observe an exit code — and which, for several
  * commits, passed every case for the same wrong reason without anyone
  * noticing. Parsing is pure, so it belongs where the message is visible. */
object CliTests extends TestSuite:

  def submitArgs(rest: String*) =
    Seq("submit", "-m", "google/veo-3.1", "--prompt-file", "p.txt") ++ rest

  val tests = Tests:

    test("a prompt is required, in one form or the other"):
      val e = errorOf("submit", "-m", "google/veo-3.1")
      assert(e.contains("a prompt is required"))

    test("--prompt alone is enough"):
      assert(submitOf("submit", "-m", "google/veo-3.1", "-p", "a duck").prompt == Some("a duck"))

    test("--prompt-file alone is enough, and -f is its short form"):
      val s = submitOf("submit", "-m", "google/veo-3.1", "-f", "p.txt")
      assert(s.promptFile.isDefined && s.prompt.isEmpty)

    test("both may be given"):
      val s = submitOf("submit", "-m", "google/veo-3.1", "-f", "p.txt", "-p", "at dusk")
      assert(s.promptFile.isDefined && s.prompt == Some("at dusk"))

    test("a missing key is a usage error, not a stack trace"):
      val errs = Cli.command.parse(Seq("check", "--job-id", "x"), Map.empty).left.toOption.get.errors
      assert(errs.exists(_.contains(Key)))

    test("list-models needs no key: the catalog is public"):
      // Verified against the API: 200 with no Authorization header at all, and
      // byte-identical content to an authenticated request.
      Cli.command.parse(Seq("list-models"), Map.empty) match
        case Right(Cmd.ListModels(key, _, _)) => assert(key.isEmpty)
        case other                         => assert(false)

    test("but list-models still sends a key when one is set"):
      Cli.command.parse(Seq("list-models"), Env) match
        case Right(Cmd.ListModels(key, _, _)) => assert(key.contains("sk-or-test"))
        case other                         => assert(false)

    test("every other subcommand still requires a key"):
      for args <- Seq(
            Seq("check", "--job-id", "x"),
            Seq("submit", "-m", "m/x", "-p", "a duck"),
            Seq("run", "-m", "m/x", "-p", "a duck"),
            Seq("download", "-u", "https://openrouter.ai/x", "--as", "o.mp4")
          )
      do assert(Cli.command.parse(args, Map.empty).isLeft)

    test("force-requires-a-download, on submit"):
      assert(errorOf(submitArgs("--force")*).contains("--force only means something alongside"))

    test("force-requires-download-as, on check too"):
      assert(errorOf("check", "--job-id", "x", "--force").contains("--force only means something"))

    test("download-as-requires-await, on submit"):
      assert(errorOf(submitArgs("--download-as", "out.mp4")*).contains("require --await"))

    test("but not on check, where a finished job already has a URL"):
      // The case `check --download-as` exists for: fetching after the fact.
      val c = checkOf("check", "--job-id", "x", "--download-as", "out.mp4")
      assert(c.download != DownloadTo.No && !c.await)

    test("await plus download-as is accepted on submit"):
      val s = submitOf(submitArgs("--await", "--download-as", "out.mp4")*)
      assert(s.await && s.download.isInstanceOf[DownloadTo.At])

    test("--download needs no argument, and is distinct from not asking"):
      assert(submitOf(submitArgs()*).download == DownloadTo.No)
      assert(submitOf(submitArgs("--await", "--download")*).download == DownloadTo.Auto)

    test("--download also requires --await on submit"):
      assert(errorOf(submitArgs("--download")*).contains("require --await"))

    test("--download alone is enough for --force"):
      assert(parse(submitArgs("--await", "--download", "--force")*).isRight)

    test("--download works on check, with no --await needed"):
      assert(checkOf("check", "--job-id", "x", "--download").download == DownloadTo.Auto)

    test("--download-as wins if both are given"):
      assert(submitOf(submitArgs("--await", "--download", "--download-as", "o.mp4")*)
        .download == DownloadTo.At(os.Path("o.mp4", os.pwd)))

    test("audio unset is distinct from audio off"):
      assert(submitOf(submitArgs()*).generateAudio.isEmpty)

    test("--generate-audio"):
      assert(submitOf(submitArgs("--generate-audio")*).generateAudio == Some(true))

    test("--no-generate-audio"):
      assert(submitOf(submitArgs("--no-generate-audio")*).generateAudio == Some(false))

    test("the two audio flags are mutually exclusive"):
      assert(parse(submitArgs("--generate-audio", "--no-generate-audio")*).isLeft)

    test("--param needs key=value"):
      assert(errorOf(submitArgs("-P", "noequals")*).contains("--param needs key=value"))

    test("--param splits on the first = only"):
      val p = submitOf(submitArgs("-P", "a=b=c")*).params.head
      assert(p.key == "a" && p.value == ujson.Str("b=c"))

    test("--param repeats"):
      assert(submitOf(submitArgs("-P", "x=1", "-P", "y=2")*).params.size == 2)

    test("a local path is refused as an image, before any request"):
      val e = errorOf(submitArgs("--first-frame", "./cat.png")*)
      assert(e.contains("by URL") && e.contains("./cat.png"))

    test("http, https and data URIs are accepted as images"):
      for u <- Seq("http://x/a.png", "https://x/a.png", "data:image/png;base64,AAAA") do
        assert(submitOf(submitArgs("--first-frame", u)*).firstFrame == Some(ImageUrl(u)))

    test("--reference repeats"):
      val s = submitOf(submitArgs("--reference", "https://x/1.png", "--reference", "https://x/2.png")*)
      assert(s.references.size == 2)

    test("receipt is off unless asked for"):
      assert(submitOf(submitArgs()*).receipt == ReceiptTo.No)

    test("--receipt derives a path"):
      assert(submitOf(submitArgs("--receipt")*).receipt == ReceiptTo.Derived)

    test("--receipt-as names one, and implies --receipt"):
      assert(submitOf(submitArgs("--receipt-as", "r.txt")*).receipt match
        case ReceiptTo.At(p) => p.last == "r.txt"
        case _               => false)

    val contentUrl = "https://openrouter.ai/api/v1/videos/j/content?index=1"

    test("download requires a url and a target"):
      assert(parse("download").isLeft)
      assert(parse("download", "--url", contentUrl).isLeft)

    test("a missing target names both spellings"):
      val e = errorOf("download", "-u", contentUrl)
      assert(e.contains("--as") && e.contains("--download-as"))

    test("--as and --download-as are synonyms"):
      def target(flag: String) = parse("download", "-u", contentUrl, flag, "out.mp4") match
        case Right(d: Cmd.Download) => Some(d.target)
        case _                      => None
      assert(target("--as").isDefined)
      assert(target("--as") == target("--download-as"))

    test("but they are alternatives, not both at once"):
      assert(parse("download", "-u", contentUrl, "--as", "a.mp4", "--download-as", "b.mp4").isLeft)

    test("download accepts an openrouter content URL"):
      parse("download", "-u", "https://openrouter.ai/api/v1/videos/j/content?index=1",
            "--download-as", "out.mp4") match
        case Right(d: Cmd.Download) => assert(d.url.contains("index=1") && d.target.last == "out.mp4")
        case other                  => assert(false)

    test("download refuses to send the key to another host"):
      val e = errorOf("download", "-u", "https://evil.example.com/steal", "--download-as", "out.mp4")
      assert(e.contains("refusing to send your API key"))
      assert(e.contains("evil.example.com"))

    test("download refuses plaintext http"):
      val e = errorOf("download", "-u", "http://openrouter.ai/x", "--download-as", "out.mp4")
      assert(e.contains("refusing to send your API key over http"))

    test("download refuses a non-URL"):
      assert(errorOf("download", "-u", "not a url", "--download-as", "out.mp4").nonEmpty)

    test("download allows an openrouter subdomain"):
      assert(parse("download", "-u", "https://cdn.openrouter.ai/x", "--download-as", "o.mp4").isRight)

    test("download does not allow a lookalike host"):
      val e = errorOf("download", "-u", "https://openrouter.ai.evil.com/x", "--download-as", "o.mp4")
      assert(e.contains("refusing to send your API key"))

    test("run is submit with three flags already set"):
      val r = submitOf("run", "-m", "google/veo-3.1", "-p", "a duck")
      assert(r.await)
      assert(r.download == DownloadTo.Auto)
      assert(r.receipt == ReceiptTo.Derived)
      assert(!r.force)

    test("run does not offer the flags it fixes"):
      for flag <- Seq("--await", "--download", "--force", "--receipt") do
        assert(parse("run", "-m", "m/x", "-p", "a duck", flag).isLeft)
      for opt <- Seq("--download-as", "--receipt-as") do
        assert(parse("run", "-m", "m/x", "-p", "a duck", opt, "o.mp4").isLeft)

    test("run still requires a model and a prompt"):
      assert(parse("run", "-p", "a duck").isLeft)
      assert(errorOf("run", "-m", "m/x").contains("a prompt is required"))

    test("run accepts every generation option submit does"):
      val r = submitOf("run", "-m", "google/veo-3.1", "-p", "a duck",
        "-d", "8", "-r", "1080p", "-a", "16:9", "--generate-audio",
        "--first-frame", "https://x/a.png", "--last-frame", "https://x/z.png",
        "--reference", "https://x/s.png", "--json", "-P", "k=1")
      assert(r.duration == Some(8) && r.resolution == Some("1080p"))
      assert(r.aspectRatio == Some("16:9") && r.generateAudio == Some(true))
      assert(r.firstFrame.isDefined && r.lastFrame.isDefined && r.references.size == 1)
      assert(r.json && r.params.size == 1)

    test("run and submit agree, given equivalent arguments"):
      // The point of sharing one option set: run must not drift from submit.
      val viaRun = submitOf("run", "-m", "m/x", "-p", "a duck", "-d", "4")
      val viaSubmit = submitOf("submit", "-m", "m/x", "-p", "a duck", "-d", "4",
        "--await", "--download", "--receipt")
      assert(viaRun == viaSubmit)

    test("an unknown subcommand is rejected"):
      assert(parse("nosuchthing").isLeft)

    test("--help is not an error"):
      assert(parse("--help").left.toOption.get.errors.isEmpty)

    test("list-models --short, long and short spellings"):
      for spelling <- Seq("--short", "-s") do
        parse("list-models", spelling) match
          case Right(Cmd.ListModels(_, _, short)) => assert(short)
          case other                              => assert(false)

    test("list-models is long by default"):
      parse("list-models") match
        case Right(Cmd.ListModels(_, _, short)) => assert(!short)
        case other                              => assert(false)

    test("--short combines with --filter"):
      parse("list-models", "-f", "veo", "-s") match
        case Right(Cmd.ListModels(_, f, short)) => assert(f == Some("veo") && short)
        case other                              => assert(false)

    test("list-models --filter"):
      assert(parse("list-models", "-f", "veo") match
        case Right(Cmd.ListModels(_, f, _)) => f == Some("veo")
        case _                           => false)
