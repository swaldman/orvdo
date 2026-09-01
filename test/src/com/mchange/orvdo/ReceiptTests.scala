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
      val out = Output(None, false, false, ReceiptTo.At(os.Path("/tmp/mine.txt")))
      assert(Main.receiptPath(out, job, Some("google/veo-3.1")) == os.Path("/tmp/mine.txt"))

    test("receiptPath: otherwise it sits beside the video"):
      val out = Output(Some(os.Path("/tmp/out/clip.mp4")), false, false, ReceiptTo.Derived)
      assert(Main.receiptPath(out, job, Some("m")) == os.Path("/tmp/out/clip.mp4.receipt"))

    test("receiptPath: with no download it is named for model and job"):
      val out = Output(None, false, false, ReceiptTo.Derived)
      val p = Main.receiptPath(out, job, Some("google/veo-3.1"))
      // A slash in a model id would otherwise read as a directory.
      assert(p.last.startsWith("google-veo-3.1-JOB123-") && p.ext == "receipt")

    test("receiptPath: an unknown model still yields a usable name"):
      val out = Output(None, false, false, ReceiptTo.Derived)
      assert(Main.receiptPath(out, job, None).last.startsWith("video-JOB123-"))

    test("ReceiptTo.No writes nothing at all"):
      withTemp: dir =>
        val before = os.list(dir).size
        val out = Output(Some(dir / "clip.mp4"), false, false, ReceiptTo.No)
        assert(run(Main.writeReceipt(out, job, None, Provenance())).isEmpty)
        assert(os.list(dir).size == before)

    test("a full receipt records the model, the digest and the prompt"):
      withTemp: dir =>
        val video = dir / "deep" / "clip.mp4"       // a directory that does not exist yet
        os.makeDir.all(video / os.up)
        os.write(video, "pretend mp4".getBytes("UTF-8"))
        val out = Output(Some(video), false, false, ReceiptTo.Derived)
        val p = run(Main.writeReceipt(out, job, Some(video), Provenance(Some(veo), Some("a duck")))).get
        val text = os.read(p)
        assert(p == dir / "deep" / "clip.mp4.receipt")
        assert(text.contains("google/veo-3.1") && text.contains("Google: Veo 3.1"))
        assert(text.contains("SHA-256") && text.contains(run(Main.sha256(video))))
        assert(text.endsWith("Prompt:\na duck\n"))

    test("a receipt with no download omits the digest"):
      withTemp: dir =>
        val out = Output(None, false, false, ReceiptTo.Derived)
        val p = run(Main.writeReceipt(out, job, None, Provenance())).get
        try assert(!os.read(p).contains("SHA-256")) finally os.remove(p)
