package com.mchange.orvdo

import utest.*
import TestSupport.*

object ModelTests extends TestSuite:
  val tests = Tests:

    test("resolution ranks by height"):
      assert(List("4K", "480p", "1080p", "2K", "720p").minBy(VideoModel.resolutionRank) == "480p")

    test("an unrecognised resolution ranks last, never as cheapest"):
      assert(VideoModel.resolutionRank("holographic") == Int.MaxValue)
      assert(List("holographic", "1080p").minBy(VideoModel.resolutionRank) == "1080p")

    test("aspect ratio ranks by long-over-short, so squarest is cheapest"):
      // At a fixed resolution the short side is pinned, so pixel count tracks
      // this ratio. The catalog agrees: seedance lists 480x480, 480x640,
      // 480x854, 480x1120 for 1:1, 3:4, 9:16, 9:21.
      assert(List("21:9", "16:9", "1:1", "9:21", "4:3").minBy(VideoModel.aspectRatioRank) == "1:1")

    test("a malformed ratio ranks last"):
      assert(VideoModel.aspectRatioRank("wide") == Double.MaxValue)
      assert(VideoModel.aspectRatioRank("0:0") == Double.MaxValue)

    test("ties keep the catalog's own order"):
      assert(List("16:9", "9:16").minBy(VideoModel.aspectRatioRank) == "16:9")
      assert(List("9:16", "16:9").minBy(VideoModel.aspectRatioRank) == "9:16")

    test("cheapest values come off the catalog"):
      assert(veo.cheapestDuration == Some(4))
      assert(veo.cheapestResolution == Some("720p"))
      assert(veo.cheapestAspectRatio == Some("16:9"))

    test("a model listing nothing yields nothing to default"):
      assert(aleph.cheapestDuration.isEmpty)
      assert(aleph.cheapestResolution.isEmpty)
      assert(aleph.cheapestAspectRatio.isEmpty)

    test("audioIsPriced asks about cost, not capability"):
      assert(veo.audioIsPriced)      // with_audio / without_audio SKUs
      assert(!gen45.audioIsPriced)   // no audio anywhere in its pricing

    test("--filter matches id or name, case-insensitively"):
      assert(veo.matches("veo") && veo.matches("VEO"))
      assert(veo.matches("google: veo"))   // via the name
      assert(!veo.matches("seedance"))

    test("terminal statuses"):
      def job(s: String) = VideoJob(id = "j", status = s)
      assert(job("completed").isTerminal && job("failed").isTerminal)
      assert(!job("pending").isTerminal && !job("in_progress").isTerminal)

    test("Param.parse reads JSON when it parses, and a string when it does not"):
      assert(Param.parse("watermark=false").toOption.get.value == ujson.Bool(false))
      assert(Param.parse("seed=42").toOption.get.value == ujson.Num(42))
      assert(Param.parse("negative_prompt=blurry").toOption.get.value == ujson.Str("blurry"))
      assert(Param.parse("""kf=[{"t":0}]""").toOption.get.value.isInstanceOf[ujson.Arr])

    test("a JSON-looking string can be forced with quoting"):
      assert(Param.parse("""version="3"""").toOption.get.value == ujson.Str("3"))

    test("Param.parse rejects a missing ="):
      assert(Param.parse("noequals").isLeft)
      assert(Param.parse("=novalue").isLeft)

    test("Passthrough nests under provider.options.<slug>.parameters"):
      val block = Passthrough.block(List("seed"), List(Param("return_last_frame", ujson.Bool(true))))
      assert(block("options")("seed")("parameters")("return_last_frame") == ujson.Bool(true))

    test("every provider tag is keyed, since only the matched one is forwarded"):
      val block = Passthrough.block(List("a", "b"), List(Param("x", ujson.Num(1))))
      assert(block("options").obj.keySet == Set("a", "b"))

    test("request fields are snake_case on the wire"):
      val json = upickle.default.write(
        VideoRequest(model = "m", prompt = "p", aspect_ratio = Some("16:9"), generate_audio = Some(false)))
      assert(json.contains("\"aspect_ratio\":\"16:9\""))
      // Some(false) must survive: the field's default is None, not false.
      assert(json.contains("\"generate_audio\":false"))

    test("unset optional fields are omitted entirely"):
      val json = upickle.default.write(VideoRequest(model = "m", prompt = "p"))
      assert(json == """{"model":"m","prompt":"p"}""")

    test("ImagePart keeps its constant `type`, which serializeDefaults would drop"):
      val json = upickle.default.write(ImagePart(ImageUrl("https://x/a.png"), Some("first_frame")))
      assert(json.contains("\"type\":\"image_url\""))
      assert(json.contains("\"frame_type\":\"first_frame\""))

    test("an input_reference is the same part without a frame_type"):
      val json = upickle.default.write(ImagePart(ImageUrl("https://x/a.png")))
      assert(json.contains("\"type\":\"image_url\"") && !json.contains("frame_type"))

    test("responses tolerate missing keys and ignore unknown ones"):
      val j = upickle.default.read[VideoJob]("""{"id":"j","status":"completed","surprise":1}""")
      assert(j.id == "j" && j.model.isEmpty)

    test("VideoJob.error accepts either a string or an object"):
      def err(s: String) = upickle.default.read[VideoJob](s).error
      assert(err("""{"id":"j","status":"failed","error":"content policy"}""").isDefined)
      assert(err("""{"id":"j","status":"failed","error":{"code":42}}""").isDefined)
