package com.mchange.orvdo

import utest.*
import TestSupport.*

/** The cost guard: what `submit` fills in when the user says nothing, and what
  * it refuses. Veo 3.1 is $0.80 for the cheapest clip and $4.80 for 8s of 4K
  * with audio, so these defaults are the difference this tool makes. */
object DefaultsTests extends TestSuite:

  def sub(rest: String*) =
    submitOf((Seq("submit", "-m", "google/veo-3.1", "--prompt-file", "p.txt") ++ rest)*)

  val tests = Tests:

    test("with nothing supplied, everything cheap is chosen and reported"):
      val (req, chosen) = Main.cheapest(sub(), "a duck", veo)
      assert(req.duration == Some(4))
      assert(req.resolution == Some("720p"))
      assert(req.aspect_ratio == Some("16:9"))
      assert(req.generate_audio == Some(false))
      assert(chosen.map(_.field) == List("duration", "resolution", "ratio", "audio"))

    test("a supplied value is used and NOT reported"):
      // The warning covers choices made on the user's behalf, not their own.
      val (req, chosen) = Main.cheapest(sub("-d", "8"), "p", veo)
      assert(req.duration == Some(8))
      assert(!chosen.exists(_.field == "duration"))
      assert(chosen.exists(_.field == "resolution"))

    test("every setting supplied means nothing to warn about"):
      val (_, chosen) = Main.cheapest(
        sub("-d", "8", "-r", "1080p", "-a", "9:16", "--generate-audio"), "p", veo)
      assert(chosen.isEmpty)

    test("audio is defaulted off only where audio is priced"):
      assert(Main.cheapest(sub(), "p", veo)._1.generate_audio == Some(false))
      // gen-4.5 prices no audio, so the field is left alone entirely.
      val (req, chosen) = Main.cheapest(sub(), "p", gen45)
      assert(req.generate_audio.isEmpty && !chosen.exists(_.field == "audio"))

    test("but an explicit audio flag is always forwarded"):
      // Detection may only ever suppress a default, never drop a user's choice.
      assert(Main.cheapest(sub("--generate-audio"), "p", gen45)._1.generate_audio == Some(true))

    test("a model that lists nothing gets nothing defaulted, and no warning"):
      val (req, chosen) = Main.cheapest(sub(), "p", aleph)
      assert(chosen.isEmpty)
      assert(req.duration.isEmpty && req.resolution.isEmpty && req.aspect_ratio.isEmpty)

    test("frame images become frame_images with a frame_type"):
      val (req, _) = Main.cheapest(
        sub("--first-frame", "https://x/a.png", "--last-frame", "https://x/z.png"), "p", veo)
      val parts = req.frame_images.get
      assert(parts.map(_.frame_type) == List(Some("first_frame"), Some("last_frame")))

    test("references become input_references with none"):
      val (req, _) = Main.cheapest(sub("--reference", "https://x/s.png"), "p", veo)
      assert(req.input_references.get.head.frame_type.isEmpty)
      assert(req.frame_images.isEmpty)

    test("canonical accepts a supported value and returns the catalog's spelling"):
      // `-r 4k` must go on the wire as `4K`.
      assert(run(Main.canonical(sub("-r", "4k"), veo)).resolution == Some("4K"))
      assert(run(Main.canonical(sub("-r", "720P"), veo)).resolution == Some("720p"))

    test("canonical rejects an unsupported resolution, naming what is offered"):
      val e = runEither(Main.canonical(sub("-r", "480p"), veo)).left.toOption.get
      assert(e.getMessage.contains("--resolution 480p is not supported"))
      assert(e.getMessage.contains("720p, 1080p, 4K"))

    test("canonical rejects an unsupported duration and ratio"):
      assert(runEither(Main.canonical(sub("-d", "7"), veo)).isLeft)
      assert(runEither(Main.canonical(sub("-a", "1:1"), veo)).isLeft)

    test("every problem is reported at once, not one per run"):
      val e = runEither(Main.canonical(sub("-d", "7", "-r", "480p", "-a", "1:1"), veo)).left.toOption.get
      assert(e.getMessage.contains("--duration") &&
             e.getMessage.contains("--resolution") &&
             e.getMessage.contains("--aspect-ratio"))

    test("a frame type the model does not accept is refused"):
      val e = runEither(Main.canonical(sub("--last-frame", "https://x/z.png"), gen45)).left.toOption.get
      assert(e.getMessage.contains("--last-frame is not supported"))
      assert(e.getMessage.contains("first_frame"))

    test("but the one it does accept is fine"):
      assert(runEither(Main.canonical(sub("--first-frame", "https://x/a.png"), gen45)).isRight)

    test("a silent catalog is treated as having no opinion, not as a refusal"):
      // aleph-2 lists no resolutions or frames; we pass through and let
      // OpenRouter decide rather than blocking on a possibly-stale catalog.
      assert(runEither(Main.canonical(sub("-r", "999p"), aleph)).isRight)
      assert(runEither(Main.canonical(sub("--first-frame", "https://x/a.png"), aleph)).isRight)
