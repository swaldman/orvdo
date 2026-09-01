package com.mchange.orvdo

import utest.*

/** The drift check: what keeps a silently-discarded wire field from going
  * unnoticed, as six catalog fields once did. */
object WireTests extends TestSuite:

  /** A stand-in for "VideoModel before the six missing fields were added". */
  final case class Skimpy(id: String, name: Option[String] = None)

  val tests = Tests:

    test("field names come off the Mirror, so they cannot go stale"):
      assert(Wire.fieldNames[Skimpy] == Set("id", "name"))
      assert(VideoJob.knownKeys.contains("unsigned_urls"))
      assert(VideoModels.knownKeys == Set("data"))

    test("unmodelled keys are reported, modelled ones are not"):
      val json = ujson.read("""{"id":"j","status":"completed","last_frame_url":"https://x/l.png"}""")
      assert(Wire.unmodeled(json, VideoJob.knownKeys) == List("last_frame_url"))

    test("a fully modelled payload reports nothing"):
      val json = ujson.read("""{"id":"j","status":"completed","usage":{"cost":1}}""")
      assert(Wire.unmodeled(json, VideoJob.knownKeys).isEmpty)

    test("only the top level, so nested unknowns are not noise"):
      val json = ujson.read("""{"id":"j","status":"c","usage":{"cost":1,"tokens_billed":900}}""")
      assert(Wire.unmodeled(json, VideoJob.knownKeys).isEmpty)

    test("across a collection the answer is unioned, not repeated"):
      // A field added to every model in the catalog is one piece of news.
      val models = List.fill(27)(ujson.read("""{"id":"m","creativity":[0,1]}"""))
      assert(Wire.unmodeledAcross(models, Wire.fieldNames[Skimpy]) == List("creativity"))

    test("the envelope level is checked too"):
      val grown = ujson.Obj("data" -> ujson.Arr(), "has_more" -> ujson.Bool(true))
      assert(Wire.unmodeled(grown, VideoModels.knownKeys) == List("has_more"))

    test("a drift check must never be the thing that fails"):
      assert(Wire.unmodeled(ujson.Num(1), Set("a")).isEmpty)
      assert(Wire.unmodeled(ujson.Str("nope"), Set("a")).isEmpty)
      assert(Wire.arrayAt(ujson.Str("nope"), "data").isEmpty)
      assert(Wire.arrayAt(ujson.Obj(), "data").isEmpty)
      assert(Wire.arrayAt(ujson.Obj("data" -> ujson.Num(1)), "data").isEmpty)

    test("arrayAt finds the elements when they are there"):
      assert(Wire.arrayAt(ujson.Obj("data" -> ujson.Arr(ujson.Num(1), ujson.Num(2))), "data").size == 2)

    test("the live VideoModel now covers what the catalog sends"):
      // A regression guard for the six fields that were being dropped.
      val sample = ujson.read(
        """{"id":"m","canonical_slug":"m-1","name":"M","description":"d","created":1,
           |"supported_durations":[4],"supported_resolutions":["720p"],
           |"supported_aspect_ratios":["16:9"],"supported_sizes":["1280x720"],
           |"supported_frame_images":["first_frame"],"generate_audio":true,"seed":true,
           |"hugging_face_id":null,"creativity":null,"upscale_factor":null,
           |"pricing_skus":{},"allowed_passthrough_parameters":[]}""".stripMargin)
      assert(Wire.unmodeled(sample, VideoModel.knownKeys).isEmpty)
