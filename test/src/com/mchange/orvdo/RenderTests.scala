package com.mchange.orvdo

import utest.*
import TestSupport.*

object RenderTests extends TestSuite:

  def job(n: Int, status: String = "completed") = upickle.default.read[VideoJob](
    s"""{"id":"JOB123","status":"$status","usage":{"cost":0.16},
        |"unsigned_urls":[${(0 until n).map(i =>
             s""""https://openrouter.ai/api/v1/videos/JOB123/content?index=$i"""").mkString(",")}]}""".stripMargin)

  val tests = Tests:

    test("one video renders as Video, several as Video[n]"):
      assert(Render.job(job(1)).contains("Video      "))
      val many = Render.job(job(3))
      assert(many.contains("Video[0]") && many.contains("Video[2]"))

    test("a completed job with no content URL says so"):
      assert(Render.job(job(0)).contains("completed but reported no content URLs"))

    test("a label at or past the column width still separates from its value"):
      // Passthrough keys are the real long labels -- `return_last_frame` is 17
      // characters against a 12-column gutter. Without the guard in `row` the
      // value runs straight into the key: `return_last_frametrue`.
      val out = Render.passthrough(List("seed"), List(Param("return_last_frame", ujson.Bool(true))), Nil)
      assert(out.contains("return_last_frame true"))

    test("a shorter label is padded to the gutter"):
      assert(Render.job(job(11)).contains("Video[10]   http"))

    test("the multi-video warning names what was skipped"):
      val w = Render.unsaved(job(3), os.Path("/tmp/clips/duck.mp4"))
      assert(w.contains("returned 3 videos") && w.contains("only index 0"))

    test("and points at our own download subcommand, not at curl"):
      val w = Render.unsaved(job(3), os.Path("/tmp/clips/duck.mp4"))
      val cmds = w.linesIterator.filter(_.trim.startsWith("orvdo download")).toList
      assert(cmds.size == 2)
      assert(cmds.head.contains("index=1") && cmds(1).contains("index=2"))
      assert(!w.contains("curl"))

    test("the emitted command never contains a key"):
      val w = Render.unsaved(job(2), os.Path("/tmp/duck.mp4"))
      assert(!w.contains("sk-or") && !w.contains("Authorization"))

    test("target names are siblings of the saved file"):
      val w = Render.unsaved(job(3), os.Path("/tmp/clips/duck.mp4"))
      assert(w.contains("/tmp/clips/duck-1.mp4") && w.contains("/tmp/clips/duck-2.mp4"))

    test("an extensionless target still gets sensible names"):
      assert(Render.unsaved(job(2), os.Path("/tmp/duck")).contains("/tmp/duck-1\""))

    test("paths and URLs are quoted, so spaces and queries survive a paste"):
      val w = Render.unsaved(job(2), os.Path("/tmp/my clip.mp4"))
      assert(w.contains("\"/tmp/my clip-1.mp4\""))
      assert(w.contains("\"https://openrouter.ai/api/v1/videos/JOB123/content?index=1\""))

    test("the defaults warning lists the picks and the alternatives"):
      val w = Render.defaults("google/veo-3.1", List(
        Chosen("duration", "4 s", List("4", "6", "8")),
        Chosen("audio", "off", List("on", "off"))))
      assert(w.contains("cheapest google/veo-3.1"))
      assert(w.contains("duration") && w.contains("(of 4, 6, 8)"))

    test("the drift warning names the scope and the fields"):
      val w = Render.unmodeled("VideoModel", List("creativity", "upscale_factor"))
      assert(w.contains("VideoModel") && w.contains("creativity, upscale_factor"))

    test("a receipt leads with the model and closes with the digest"):
      val r = Render.receipt(Some(veo), job(1),
        Some(os.Path("/tmp/duck.mp4") -> "abc123"), Some("a duck in a hat"))
      val lines = r.linesIterator.toList
      assert(lines.head.startsWith("Model") && lines.head.contains("google/veo-3.1"))
      assert(lines(1).contains("Google: Veo 3.1") && lines(2).contains("veo-3.1-20260320"))
      assert(r.contains("SHA-256") && r.contains("abc123"))
      assert(r.endsWith("Prompt:\na duck in a hat"))

    test("a receipt without a download has no Saved or digest rows"):
      val r = Render.receipt(Some(veo), job(1), None, None)
      assert(!r.contains("SHA-256") && !r.contains("Saved") && !r.contains("Prompt:"))

    test("the catalog block suppresses the job's own duplicate Model row"):
      val withModel = job(1).copy(model = Some("google/veo-3.1"))
      val r = Render.receipt(Some(veo), withModel, None, None)
      assert(r.linesIterator.count(_.startsWith("Model")) == 1)

    test("with no catalog entry the job's own model row is kept"):
      val withModel = job(1).copy(model = Some("google/veo-3.1"))
      assert(Render.receipt(None, withModel, None, None).contains("Model"))

    test("a receipt records what we asked for, not just what came back"):
      val body = VideoRequest.body(VideoRequest(
        model = "google/veo-3.1", prompt = "a duck", duration = Some(8),
        resolution = Some("1080p"), aspect_ratio = Some("16:9"),
        generate_audio = Some(true)))
      val r = Render.receipt(Some(veo), job(1), None, Some("a duck"), Some(body))
      assert(r.contains("Request:"))
      assert(r.contains("duration") && r.contains("8"))
      assert(r.contains("resolution") && r.contains("1080p"))
      assert(r.contains("aspect_ratio") && r.contains("16:9"))
      assert(r.contains("generate_audio") && r.contains("true"))

    test("the Request section skips model and prompt, shown elsewhere"):
      val body = VideoRequest.body(VideoRequest(model = "m", prompt = "p", duration = Some(4)))
      val section = Render.receipt(None, job(1), None, None, Some(body))
        .linesIterator.dropWhile(_ != "Request:").toList
      assert(section.exists(_.contains("duration")))
      assert(!section.exists(_.trim.startsWith("model")) && !section.exists(_.trim.startsWith("prompt")))

    test("a request with nothing but model and prompt yields no Request section"):
      val body = VideoRequest.body(VideoRequest(model = "m", prompt = "p"))
      assert(!Render.receipt(None, job(1), None, None, Some(body)).contains("Request:"))

    test("frame images read as frame_type=url, not raw content parts"):
      val body = VideoRequest.body(VideoRequest(model = "m", prompt = "p",
        frame_images = Some(List(
          ImagePart(ImageUrl("https://x/a.png"), Some("first_frame")),
          ImagePart(ImageUrl("https://x/z.png"), Some("last_frame")))),
        input_references = Some(List(ImagePart(ImageUrl("https://x/s.png"))))))
      val r = Render.receipt(None, job(1), None, None, Some(body))
      assert(r.contains("first_frame=https://x/a.png"))
      assert(r.contains("last_frame=https://x/z.png"))
      assert(r.contains("input_references") && r.contains("https://x/s.png"))
      assert(!r.contains("image_url"))   // the raw shape must not leak through

    test("passthrough parameters are flattened under their provider tag"):
      val body = VideoRequest.body(
        VideoRequest(model = "m", prompt = "p"),
        Some(Passthrough.block(List("seed"), List(Param("return_last_frame", ujson.Bool(true))))))
      val r = Render.receipt(None, job(1), None, None, Some(body))
      assert(r.contains("passthrough") && r.contains("seed: return_last_frame=true"))

    test("the Request section reads off the JSON, so new fields need no upkeep"):
      // A field VideoRequest does not model at all still appears.
      val body = VideoRequest.body(VideoRequest(model = "m", prompt = "p"))
      body.obj("some_future_field") = ujson.Str("x")
      assert(Render.receipt(None, job(1), None, None, Some(body)).contains("some_future_field"))

    test("Request sits between the digest and the prompt"):
      val body = VideoRequest.body(VideoRequest(model = "m", prompt = "p", duration = Some(4)))
      val r = Render.receipt(Some(veo), job(1), Some(os.Path("/tmp/x.mp4") -> "abc"), Some("a duck"), Some(body))
      assert(r.indexOf("SHA-256") < r.indexOf("Request:"))
      assert(r.indexOf("Request:") < r.indexOf("Prompt:"))

    test("a long description wraps rather than being cut off"):
      val wordy = upickle.default.read[VideoModel](
        s"""{"id":"m/x","description":"${List.fill(60)("alpha").mkString(" ")}"}""")
      val out = Render.models(List(wordy), None)
      assert(!out.contains("..."))
      assert("alpha".r.findAllIn(out).size == 60)   // every word survives

    test("wrapped lines align under the value column, and fit the width"):
      val wordy = upickle.default.read[VideoModel](
        s"""{"id":"m/x","description":"${List.fill(60)("alpha").mkString(" ")}"}""")
      val lines = Render.models(List(wordy), None).linesIterator.toList
      val about = lines.dropWhile(!_.contains("about"))
      assert(about.tail.forall(_.startsWith(" " * 14)))
      assert(about.forall(_.length <= 80))

    test("a short description stays on one line"):
      val brief = upickle.default.read[VideoModel]("""{"id":"m/x","description":"A small model."}""")
      val about = Render.models(List(brief), None).linesIterator.filter(_.contains("about")).toList
      assert(about == List("  about       A small model."))

    test("an over-long word is left whole rather than broken"):
      // Breaking a URL across lines would be worse than a long line.
      val url = "https://example.com/" + "x" * 90
      val m = upickle.default.read[VideoModel](s"""{"id":"m/x","description":"see $url now"}""")
      assert(Render.models(List(m), None).contains(url))

    test("--short is one line per model, and no blank lines"):
      val out = Render.models(List(veo, gen45, aleph), None, short = true)
      val lines = out.linesIterator.toList
      assert(lines.size == 3)
      assert(lines.forall(_.nonEmpty))

    test("--short keeps the same heading as the full listing"):
      // The two must not come to disagree about what a model is called.
      val full = Render.models(List(veo), None).linesIterator.next()
      val short = Render.models(List(veo), None, short = true)
      assert(short == full)

    test("--short omits everything else"):
      val out = Render.models(List(veo), None, short = true)
      assert(!out.contains("durations") && !out.contains("pricing") && !out.contains("about"))

    test("--short sorts by id, like the full listing"):
      val out = Render.models(List(gen45, aleph, veo), None, short = true)
      assert(out.linesIterator.toList == out.linesIterator.toList.sorted)

    test("--short still explains an empty result"):
      assert(Render.models(Nil, Some("zzz"), short = true).contains("match 'zzz'"))

    test("list-models distinguishes empty from filtered-to-empty"):
      assert(Render.models(Nil, None).contains("No video generation models available"))
      assert(Render.models(Nil, Some("zzz")).contains("match 'zzz'"))

    test("a model block omits fields the catalog left null"):
      val block = Render.models(List(aleph), None)
      assert(block.contains("runway/aleph-2"))
      assert(!block.contains("durations") && !block.contains("resolutions"))

    test("capability booleans render as yes/no"):
      val block = Render.models(List(veo), None)
      assert(block.contains("audio       yes") && block.contains("seed        yes"))
