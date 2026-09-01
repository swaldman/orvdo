package orvdo

import upickle.default.ReadWriter

/** Wire types for OpenRouter's video generation API.
  *
  * Fields are named exactly as they appear on the wire, so uPickle's derived
  * ReadWriters need no key mapping or custom encoders. Two uPickle behaviours
  * do the rest of the work for us:
  *
  *   - fields whose value equals their default are omitted when writing, so an
  *     unset `Option` never shows up in the request body at all;
  *   - unknown keys are ignored when reading, so OpenRouter can add fields
  *     without breaking us.
  */

final case class ImageUrl(url: String) derives ReadWriter

/** One OpenAI-style content part, serving both `frame_images` (where
  * `frame_type` says which end of the clip it pins) and `input_references`
  * (where it is absent and the image is style or subject guidance).
  *
  * OpenRouter takes these by URL it can fetch, not by upload.
  */
final case class ImagePart(
    image_url: ImageUrl,
    frame_type: Option[String] = None,
    // Constant but required, which is exactly the case the global
    // `serializeDefaults = false` gets wrong: the field always equals its
    // default, so it would always be dropped. This is the documented per-field
    // escape hatch, and the only place the codebase needs it.
    @upickle.implicits.serializeDefaults(true) `type`: String = "image_url"
) derives ReadWriter

final case class VideoRequest(
    model: String,
    prompt: String,
    duration: Option[Int] = None,
    resolution: Option[String] = None,
    aspect_ratio: Option[String] = None,
    generate_audio: Option[Boolean] = None,
    seed: Option[Int] = None,
    frame_images: Option[List[ImagePart]] = None,
    input_references: Option[List[ImagePart]] = None,
    callback_url: Option[String] = None
) derives ReadWriter

final case class Usage(
    cost: Option[Double] = None,
    is_byok: Option[Boolean] = None
) derives ReadWriter

/** A parsed response paired with the JSON it was parsed from.
  *
  * `VideoJob` is deliberately lossy: `allowUnknownKeys` is on, so any field
  * OpenRouter sends that we do not model is dropped without a murmur. That is
  * the right call for forward compatibility and the wrong one for noticing a
  * new output, so the operations that return a job hand back the raw value too.
  * `--json` prints this rather than re-serializing the case class, and
  * `Render.job` diffs it to warn about fields we ignored.
  */
final case class Raw[+A](value: A, json: ujson.Value)

final case class VideoJob(
    id: String,
    status: String,
    model: Option[String] = None,
    generation_id: Option[String] = None,
    polling_url: Option[String] = None,
    unsigned_urls: Option[List[String]] = None,
    // `error` is a bare string on some failures and an object on others.
    error: Option[ujson.Value] = None,
    usage: Option[Usage] = None
) derives ReadWriter:

  /** pending | in_progress | completed | failed | cancelled | expired */
  def isTerminal: Boolean = VideoJob.terminalStatuses.contains(status)

  def contentUrls: List[String] = unsigned_urls.getOrElse(Nil)

  def firstContentUrl: Option[String] = contentUrls.headOption

object VideoJob:
  val terminalStatuses: Set[String] = Set("completed", "failed", "cancelled", "expired")

  val knownKeys: Set[String] = Wire.fieldNames[VideoJob]

final case class VideoModel(
    id: String,
    canonical_slug: Option[String] = None,
    name: Option[String] = None,
    description: Option[String] = None,
    created: Option[Long] = None,
    supported_durations: Option[List[Int]] = None,
    supported_resolutions: Option[List[String]] = None,
    supported_aspect_ratios: Option[List[String]] = None,
    supported_sizes: Option[List[String]] = None,
    supported_frame_images: Option[List[String]] = None,
    // Capability booleans: whether the model accepts the request field of the
    // same name at all. Distinct from whether using it costs extra, which is a
    // question only `pricing_skus` can answer — see `audioIsPriced`.
    generate_audio: Option[Boolean] = None,
    seed: Option[Boolean] = None,
    hugging_face_id: Option[String] = None,
    // `ujson.Value` for the same reason as `pricing_skus`: one model in the
    // catalog carries each of these, and the two disagree about how to express
    // what looks like the same idea — `creativity` is the array [0, 1] while
    // `upscale_factor` is the object {"min": 1.5, "max": 3}. One sample apiece
    // is not enough to commit a shape, and guessing wrong would fail the whole
    // listing rather than one field.
    creativity: Option[ujson.Value] = None,
    upscale_factor: Option[ujson.Value] = None,
    pricing_skus: Option[Map[String, ujson.Value]] = None,
    allowed_passthrough_parameters: Option[List[String]] = None
) derives ReadWriter:

  /** What `list-models --filter` restricts on: a case-insensitive substring of
    * either the slug or the display name. Ids are lowercase and names are not,
    * so matching case-sensitively would make `--filter Veo` and `--filter veo`
    * return different halves of the same family. */
  def matches(needle: String): Boolean =
    val n = needle.toLowerCase
    id.toLowerCase.contains(n) || name.exists(_.toLowerCase.contains(n))

  /** The least expensive value of each kind this model offers. `minByOption`
    * keeps the first of equal ranks, so ties fall back to the catalog's own
    * ordering rather than to something arbitrary. A model that lists nothing
    * yields `None` and is simply left unset. */
  def cheapestDuration: Option[Int] = supported_durations.flatMap(_.minOption)

  def cheapestResolution: Option[String] =
    supported_resolutions.flatMap(_.minByOption(VideoModel.resolutionRank))

  def cheapestAspectRatio: Option[String] =
    supported_aspect_ratios.flatMap(_.minByOption(VideoModel.aspectRatioRank))

  /** Whether audio is a *priced* dimension for this model — a SKU that
    * distinguishes with from without, or an `audio` passthrough parameter.
    *
    * Deliberately not "can this model produce sound". `openai/sora-2-pro` bills
    * `duration_seconds_720p` with no audio split, so audio comes bundled and
    * there is no surprise on the invoice to protect anyone from. Since the
    * whole point here is cost, the narrower test is the useful one: it is what
    * decides whether defaulting `generate_audio` is worth doing or warning
    * about, and on a model that never mentions audio the field is at best
    * noise and at worst a 400. */
  def audioIsPriced: Boolean =
    def mentionsAudio(ss: Iterable[String]) = ss.exists(_.toLowerCase.contains("audio"))
    pricing_skus.exists(p => mentionsAudio(p.keys)) ||
      allowed_passthrough_parameters.exists(mentionsAudio)

object VideoModel:

  val knownKeys: Set[String] = Wire.fieldNames[VideoModel]

  /** `720p` -> 720, `4K` -> 2160. Anything we do not recognise ranks last, so
    * an unfamiliar label is never mistaken for the cheapest thing on offer. */
  def resolutionRank(label: String): Int =
    val l = label.trim.toLowerCase
    Map("2k" -> 1440, "4k" -> 2160, "8k" -> 4320)
      .get(l)
      .orElse(l.stripSuffix("p").toIntOption)
      .getOrElse(Int.MaxValue)

  /** Long side over short side, so the shape closest to square ranks first.
    * At a fixed resolution the short side is pinned and pixel count tracks that
    * ratio, which the catalog bears out: seedance offers 480x480, 480x640,
    * 480x854, 480x1120 for 1:1, 3:4, 9:16, 9:21 respectively. */
  def aspectRatioRank(label: String): Double =
    label.split(":").map(_.trim.toDoubleOption) match
      case Array(Some(w), Some(h)) if w > 0 && h > 0 => math.max(w, h) / math.min(w, h)
      case _                                         => Double.MaxValue

/** A model-specific passthrough parameter, already parsed to its JSON value. */
final case class Param(key: String, value: ujson.Value)

object Param:
  /** `key=value`, splitting at the first `=` so values may contain more.
    *
    * The value is read as JSON when it parses and treated as a plain string
    * when it does not, which is what makes `watermark=false` a boolean and
    * `negative_prompt=blurry, low quality` a string without any ceremony. A
    * value that must stay a string despite looking like JSON can be forced
    * with shell quoting: `version='"3"'`. */
  def parse(s: String): Either[String, Param] =
    s.split("=", 2) match
      case Array(k, v) if k.trim.nonEmpty =>
        Right(Param(k.trim, scala.util.Try(ujson.read(v)).getOrElse(ujson.Str(v))))
      case _ =>
        Left(s"--param needs key=value, got '$s'")

/** The provider-keyed envelope passthrough parameters travel in. OpenRouter
  * forwards only the block whose key matches the provider it routes to, so a
  * wrong slug is discarded in silence rather than rejected — which is why
  * `submit` resolves the slug rather than guessing it, and reports what it
  * used. */
object Passthrough:
  def block(tags: List[String], params: List[Param]): ujson.Value =
    val parameters = ujson.Obj.from(params.map(p => p.key -> p.value))
    ujson.Obj(
      "options" -> ujson.Obj.from(tags.map(_ -> ujson.Obj("parameters" -> parameters)))
    )

/** `endpoints[].tag` on `/models/{id}/endpoints` is the provider slug that
  * `provider.options` is keyed by — `google-vertex` for Veo, `seed` for
  * Seedance. The video catalog does not carry it, so it takes its own call. */
final case class Endpoint(tag: Option[String] = None, provider_name: Option[String] = None)
    derives ReadWriter

final case class EndpointList(endpoints: List[Endpoint] = Nil) derives ReadWriter
final case class EndpointsEnvelope(data: EndpointList) derives ReadWriter

/** One request field the CLI filled in on the user's behalf, with what else the
  * model offered, so the warning can show the road not taken. */
final case class Chosen(field: String, value: String, offered: List[String])

final case class VideoModels(data: List[VideoModel]) derives ReadWriter

object VideoModels:
  val knownKeys: Set[String] = Wire.fieldNames[VideoModels]
