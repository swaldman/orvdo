package com.mchange.orvdo

import utest.*
import TestSupport.*

/** Digest, filename derivation, and the receipt file itself. These touch the
  * filesystem but never the network. */
object ReceiptTests extends TestSuite:

  val job = upickle.default.read[VideoJob](
    """{"id":"JOB123","status":"completed","unsigned_urls":["https://x/v.mp4"],
       |"usage":{"cost":0.054}}""".stripMargin)

  def withTemp[A](f: os.Path => A): A =
    val dir = os.temp.dir(prefix = "orvdo-test")
    try f(dir) finally os.remove.all(dir)

  val tests = Tests:

    test("sha256 matches the reference digest for the empty input"):
      withTemp: dir =>
        val f = dir / "empty"
        os.write(f, Array.emptyByteArray)
        assert(run(Main.sha256(f)) ==
          "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")

    test("and for a known string"):
      withTemp: dir =>
        val f = dir / "abc"
        os.write(f, "abc".getBytes("UTF-8"))
        assert(run(Main.sha256(f)) ==
          "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")

    test("bytes above 0x7f are masked, not sign-extended"):
      // Without `& 0xff` a negative byte formats as eight hex digits and every
      // digest silently becomes wrong.
      withTemp: dir =>
        val f = dir / "allbytes"
        os.write(f, (0 to 255).map(_.toByte).toArray)
        val d = run(Main.sha256(f))
        assert(d.length == 64 && d.forall(c => "0123456789abcdef".contains(c)))

    test("receiptPath: an explicit path wins"):
      val out = Output(DownloadTo.No, false, false, ReceiptTo.At(os.Path("/tmp/mine.txt")))
      assert(Main.receiptPath(out, None, job, Some("google/veo-3.1")) == os.Path("/tmp/mine.txt"))

    test("receiptPath: otherwise it sits beside the video"):
      val out = Output(DownloadTo.At(os.Path("/tmp/out/clip.mp4")), false, false, ReceiptTo.Derived)
      assert(Main.receiptPath(out, Some(os.Path("/tmp/out/clip.mp4")), job, Some("m")) == os.Path("/tmp/out/clip.mp4.receipt"))

    test("receiptPath: with no download it is named for model and job"):
      val out = Output(DownloadTo.No, false, false, ReceiptTo.Derived)
      val p = Main.receiptPath(out, None, job, Some("google/veo-3.1"))
      // A slash in a model id would otherwise read as a directory.
      assert(p.last.startsWith("google-veo-3.1-JOB123-") && p.ext == "receipt")

    test("receiptPath: an unknown model still yields a usable name"):
      val out = Output(DownloadTo.No, false, false, ReceiptTo.Derived)
      assert(Main.receiptPath(out, None, job, None).last.startsWith("video-JOB123-"))

    test("ReceiptTo.No writes nothing at all"):
      withTemp: dir =>
        val before = os.list(dir).size
        val out = Output(DownloadTo.At(dir / "clip.mp4"), false, false, ReceiptTo.No)
        assert(run(Main.writeReceipt(out, job, None, Provenance())).isEmpty)
        assert(os.list(dir).size == before)

    test("a full receipt records the model, the digest and the prompt"):
      withTemp: dir =>
        val video = dir / "deep" / "clip.mp4"       // a directory that does not exist yet
        os.makeDir.all(video / os.up)
        os.write(video, "pretend mp4".getBytes("UTF-8"))
        val out = Output(DownloadTo.At(video), false, false, ReceiptTo.Derived)
        val (p, diverted) = run(Main.writeReceipt(out, job, Some(video), Provenance(Some(veo), Some("a duck")))).get
        assert(diverted.isEmpty)
        val text = os.read(p)
        assert(p == dir / "deep" / "clip.mp4.receipt")
        assert(text.contains("google/veo-3.1") && text.contains("Google: Veo 3.1"))
        assert(text.contains("SHA-256") && text.contains(run(Main.sha256(video))))
        assert(text.endsWith("Prompt:\na duck\n"))

    test("a file-only prompt is its trimmed contents"):
      withTemp: dir =>
        os.write(dir / "p.txt", "  a duck in a hat\n\n")
        assert(run(Main.readPrompt(Some(dir / "p.txt"), None)) == "a duck in a hat")

    test("an argument-only prompt is itself"):
      assert(run(Main.readPrompt(None, Some("a duck in a hat"))) == "a duck in a hat")

    test("both: the file leads, a blank line, then the argument"):
      withTemp: dir =>
        os.write(dir / "p.txt", "a duck in a hat\n")
        assert(run(Main.readPrompt(Some(dir / "p.txt"), Some("at dusk"))) ==
          "a duck in a hat\n\nat dusk")

    test("a missing prompt file is named, not merely absent"):
      withTemp: dir =>
        val e = runEither(Main.readPrompt(Some(dir / "nope.txt"), Some("x"))).left.toOption.get
        assert(e.getMessage.contains("no such prompt file"))

    test("an empty prompt file is still an error, even alongside an argument"):
      withTemp: dir =>
        os.write(dir / "empty.txt", "   \n")
        assert(runEither(Main.readPrompt(Some(dir / "empty.txt"), Some("x"))).isLeft)

    test("an empty argument with no file is an empty prompt"):
      assert(runEither(Main.readPrompt(None, Some("   "))).isLeft)

    test("an auto name is derived from the job and the media type"):
      assert(Main.autoName(job, 0, 1, "mp4") == "video_JOB123.mp4")

    test("and is numbered only when there is more than one output"):
      assert(Main.autoName(job, 0, 3, "mp4") == "video_JOB123_0.mp4")
      assert(Main.autoName(job, 2, 3, "mp4") == "video_JOB123_2.mp4")

    test("an auto name sanitises the job id like any other"):
      val odd = upickle.default.read[VideoJob]("""{"id":"a/b c","status":"completed"}""")
      assert(Main.autoName(odd, 0, 1, "mp4") == "video_a-b-c.mp4")

    test("the extension comes from the declared media type"):
      def ext(ct: Option[String]) = Fetched(Array.emptyByteArray, ct).extension
      assert(ext(Some("video/mp4")) == "mp4")
      assert(ext(Some("video/mp4; charset=binary")) == "mp4")   // parameters ignored
      assert(ext(Some("VIDEO/MP4")) == "mp4")                   // case-insensitive
      assert(ext(Some("image/png")) == "png")

    test("an unknown or absent type does not get a guessed extension"):
      // `bin` is unhelpful, but it does not claim to be something it is not.
      assert(Fetched(Array.emptyByteArray, Some("application/octet-stream")).extension == "bin")
      assert(Fetched(Array.emptyByteArray, None).extension == "bin")

    test("annotated names carry the job id, keeping any extension"):
      assert(Main.annotated(os.Path("/a/clip.mp4"), "JOB123") == os.Path("/a/clip_JOB123.mp4"))
      assert(Main.annotated(os.Path("/a/clip"), "JOB123") == os.Path("/a/clip_JOB123"))
      assert(Main.annotated(os.Path("/a/two.dots.mp4"), "J") == os.Path("/a/two.dots_J.mp4"))

    test("a job id is sanitised before it becomes a filename"):
      // Job ids are opaque; a separator in one must not become a directory.
      assert(Main.annotated(os.Path("/a/clip.mp4"), "a/b c").last == "clip_a-b-c.mp4")

    test("freeTarget takes the requested path when it is free"):
      withTemp: dir =>
        assert(Main.freeTarget(dir / "clip.mp4", "J", false) == Target.Requested(dir / "clip.mp4"))

    test("freeTarget diverts rather than refusing when the name is taken"):
      withTemp: dir =>
        os.write(dir / "clip.mp4", "occupied")
        assert(Main.freeTarget(dir / "clip.mp4", "JOB", false) ==
          Target.Diverted(dir / "clip_JOB.mp4", dir / "clip.mp4"))

    test("freeTarget blocks only when the fallback is taken too"):
      withTemp: dir =>
        os.write(dir / "clip.mp4", "occupied")
        os.write(dir / "clip_JOB.mp4", "also occupied")
        assert(Main.freeTarget(dir / "clip.mp4", "JOB", false) ==
          Target.Blocked(dir / "clip.mp4", dir / "clip_JOB.mp4"))

    test("--force skips diversion entirely: the caller accepted the loss"):
      withTemp: dir =>
        os.write(dir / "clip.mp4", "occupied")
        assert(Main.freeTarget(dir / "clip.mp4", "JOB", true) == Target.Requested(dir / "clip.mp4"))

    test("urlTag picks the job id out of a content URL"):
      assert(Main.urlTag("https://openrouter.ai/api/v1/videos/B7pFInV/content?index=1") == "B7pFInV")

    test("and falls back to something stable when the URL is another shape"):
      val tag = Main.urlTag("https://openrouter.ai/something/else")
      assert(tag.nonEmpty && !tag.contains("/"))

    test("a receipt no longer clobbers; it diverts and says so"):
      withTemp: dir =>
        val video = dir / "clip.mp4"
        os.write(video, "pretend mp4")
        os.write(dir / "clip.mp4.receipt", "an older receipt, not to be lost")
        val out = Output(DownloadTo.At(video), false, false, ReceiptTo.Derived)
        val (p, diverted) = run(Main.writeReceipt(out, job, Some(video), Provenance())).get
        assert(p == dir / "clip.mp4_JOB123.receipt")
        assert(diverted == Some(dir / "clip.mp4.receipt"))
        assert(os.read(dir / "clip.mp4.receipt") == "an older receipt, not to be lost")

    test("a receipt with nowhere to go fails rather than overwriting"):
      withTemp: dir =>
        val video = dir / "clip.mp4"
        os.write(video, "pretend mp4")
        os.write(dir / "clip.mp4.receipt", "first")
        os.write(dir / "clip.mp4_JOB123.receipt", "second")
        val out = Output(DownloadTo.At(video), false, false, ReceiptTo.Derived)
        runEither(Main.writeReceipt(out, job, Some(video), Provenance())).left.toOption.get match
          case n: exception.NotSaved => assert(n.what == "the receipt" && n.url.isEmpty)
          case other                 => assert(false)

    test("a receipt with no download omits the digest"):
      withTemp: dir =>
        val out = Output(DownloadTo.No, false, false, ReceiptTo.Derived)
        val (p, _) = run(Main.writeReceipt(out, job, None, Provenance())).get
        try assert(!os.read(p).contains("SHA-256")) finally os.remove(p)
