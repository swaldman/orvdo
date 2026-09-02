package com.mchange.orvdo

import java.time.{Instant, ZoneOffset}

object Render:

  private val LabelWidth = 12

  private def row(label: String, value: String): String =
    // A label at or past the column width still needs a separator, or a long
    // passthrough key runs straight into its value.
    if label.length >= LabelWidth then s"$label $value"
    else label.padTo(LabelWidth, ' ') + value

  /** A one-line rendering of any JSON, so a field whose shape we deliberately
    * did not commit to still shows the reader what arrived. */
  private def compact(v: ujson.Value): String = v match
    case ujson.Arr(vs)  => vs.map(scalar).mkString(", ")
    case ujson.Obj(kvs) => kvs.map((k, x) => s"$k=${scalar(x)}").mkString(", ")
    case other          => scalar(other)

  private def yesNo(b: Boolean): String = if b then "yes" else "no"

  private def date(epochSeconds: Long): String =
    Instant.ofEpochSecond(epochSeconds).atZone(ZoneOffset.UTC).toLocalDate.toString

  private def scalar(v: ujson.Value): String = v match
    case ujson.Str(s)  => s
    case ujson.Num(n)  => if n == n.floor then n.toLong.toString else n.toString
    case ujson.Bool(b) => b.toString
    case other         => ujson.write(other)

  /** The stderr warning naming every setting we picked for the user and what
    * else was available, so that a disappointing clip is explicable rather than
    * mysterious — and so the cost of the alternatives is visible before the
    * next run. */
  def defaults(modelId: String, chosen: List[Chosen]): String =
    val out = List.newBuilder[String]
    out += s"warning: filling in unset quality settings with the cheapest $modelId offers,"
    out += "         so expect the lowest quality this model produces."
    chosen.foreach { c =>
      out += "  " + row(c.field, c.value.padTo(10, ' ') + s"(of ${c.offered.mkString(", ")})")
    }
    out += "         Override with --duration / --resolution / --aspect-ratio /"
    out += "         --generate-audio / --no-generate-audio."
    out.result().mkString("\n")

  /** What we are forwarding, under which provider slug, and whether the catalog
    * recognises it. OpenRouter drops options keyed to a provider it did not
    * route to without complaint, so showing the slug is the only way an
    * ignored parameter is distinguishable from an accepted one. */
  def passthrough(tags: List[String], params: List[Param], unlisted: List[String]): String =
    val out = List.newBuilder[String]
    out += s"passthrough: forwarding to provider ${tags.mkString(", ")}"
    params.foreach(p => out += "  " + row(p.key, ujson.write(p.value)))
    if unlisted.nonEmpty then
      out += s"warning: ${unlisted.mkString(", ")} not in this model's allowed_passthrough_parameters;"
      out += "         sending anyway, but OpenRouter may drop it without saying so."
    out.result().mkString("\n")

  /** Fields the wire sent that our types discard.
    *
    * Diagnostic rather than result, so this goes to stderr: it describes our
    * parser, not the job or the catalog, and a redirected record should not
    * collect it. */
  def unmodeled(scope: String, keys: List[String]): String =
    s"warning: ${keys.size} field(s) not modelled by $scope, and so discarded: " +
      keys.mkString(", ") + "\n         add them to the case class to keep them."

  /** Aligned with the job rows above it, since it is printed just beneath them. */
  def saved(path: os.Path): String = row("Saved", path.toString)

  def receiptAt(path: os.Path): String = row("Receipt", path.toString)

  /** `clip.mp4` beside index 2 becomes `clip-2.mp4`, in the same directory. */
  private def sibling(saved: os.Path, index: Int): os.Path =
    val suffix = if saved.ext.isEmpty then "" else "." + saved.ext
    saved / os.up / s"${saved.baseName}-$index$suffix"

  /** The content URLs `--download-as` had no name for, with a command that
    * fetches each.
    *
    * A bare list would not be much use: content URLs are "unsigned" only in
    * carrying no signature of their own, and still require the bearer token, so
    * pasting one into a browser earns a 401. The `download` subcommand exists
    * for exactly this, and is what we point at rather than curl — it handles
    * the key, and it is code with tests behind it. */
  def unsaved(j: VideoJob, saved: os.Path): String =
    val urls = j.contentUrls
    val out = List.newBuilder[String]
    out += s"warning: job ${j.id} returned ${urls.size} videos; only index 0 was saved to"
    out += s"         $saved. Fetch the rest with:"
    urls.zipWithIndex.drop(1).foreach { (url, i) =>
      out += "  orvdo download --url \"" + url + "\" --download-as \"" + sibling(saved, i) + "\""
    }
    out.result().mkString("\n")

  /** `frame_images` and `input_references` as `first_frame=<url>` rather than
    * the raw content-part objects, which are unreadable on one line. */
  private def images(v: ujson.Value): String =
    v.arrOpt.toList.flatten.map { part =>
      val o = part.objOpt
      val url = o.flatMap(_.get("image_url")).flatMap(_.objOpt).flatMap(_.get("url"))
        .map(scalar).getOrElse("?")
      o.flatMap(_.get("frame_type")).map(t => s"${scalar(t)}=$url").getOrElse(url)
    }.mkString(", ")

  /** `provider.options.<tag>.parameters` flattened to `<tag>: k=v, k=v`. */
  private def passthroughOf(v: ujson.Value): String =
    v.objOpt.flatMap(_.get("options")).flatMap(_.objOpt).toList.flatMap(_.toList).map { (tag, cfg) =>
      s"$tag: " + cfg.objOpt.flatMap(_.get("parameters")).map(compact).getOrElse("")
    }.mkString("; ")

  private def requestValue(key: String, v: ujson.Value): String = key match
    case "frame_images" | "input_references" => images(v)
    case "provider"                          => passthroughOf(v)
    case _                                   => compact(v)

  /** What we asked for, as opposed to what came back.
    *
    * Read off the JSON that was sent rather than enumerated from
    * `VideoRequest`, so a field added there appears here without anyone having
    * to remember. `model` and `prompt` are skipped: the receipt already shows
    * both, one at the top and one at the bottom. */
  private def requestSection(body: ujson.Value): List[String] =
    val rows = body.objOpt.toList
      .flatMap(_.toList)
      .filterNot((k, _) => k == "model" || k == "prompt")
      .map((k, v) => (if k == "provider" then "passthrough" else k) -> requestValue(k, v))
    if rows.isEmpty then Nil
    else
      val width = math.max(LabelWidth, rows.map(_._1.length).max + 2)
      "" :: "Request:" :: rows.map((label, value) => "  " + label.padTo(width, ' ') + value)

  /** A standalone record of how a video came to exist, meant to outlive the
    * shell that made it.
    *
    * The model block leads because that is what a file on disk cannot tell you
    * later; the digest closes the key/value section so the record can be
    * checked against the file it describes; the prompt goes last and free-form,
    * since it is the only part that is prose and may run to many lines.
    */
  def receipt(
      model: Option[VideoModel],
      j: VideoJob,
      saved: Option[(os.Path, String)],
      prompt: Option[String],
      body: Option[ujson.Value] = None
  ): String =
    val out = List.newBuilder[String]

    model.foreach { m =>
      out += row("Model", m.id)
      m.name.foreach(n => out += row("Name", n))
      m.canonical_slug.foreach(sl => out += row("Slug", sl))
    }

    // The catalog block above already names the model, so the job's own copy
    // would only repeat it. Dropped here rather than by a flag on `job`, which
    // keeps that function's contract simple.
    out += job(if model.isDefined then j.copy(model = None) else j)

    saved.foreach { (path, digest) =>
      out += row("Saved", path.toString)
      out += row("SHA-256", digest)
    }

    body.foreach(b => requestSection(b).foreach(out += _))

    prompt.foreach(p => out += s"\nPrompt:\n$p")

    out.result().mkString("\n")

  /** Everything we know about a job, including where to fetch the video. */
  def job(j: VideoJob): String =
    val out = List.newBuilder[String]
    out += row("Job", j.id)
    out += row("Status", j.status)
    j.model.foreach(m => out += row("Model", m))
    j.generation_id.foreach(g => out += row("Generation", g))
    j.usage.flatMap(_.cost).foreach(c => out += row("Cost", "$" + f"$c%.4f"))
    j.usage.flatMap(_.is_byok).filter(identity).foreach(_ => out += row("BYOK", "yes"))
    j.polling_url.foreach(u => out += row("Poll", u))

    j.contentUrls match
      case Nil => ()
      case one :: Nil => out += row("Video", one)
      case many => many.zipWithIndex.foreach { case (u, i) => out += row(s"Video[$i]", u) }

    j.error.foreach(e => out += row("Error", scalar(e)))

    if j.status == "completed" && j.contentUrls.isEmpty then
      out += row("Note", "job completed but reported no content URLs")

    out.result().mkString("\n")

  /** One block per model, with everything you need to build a valid request.
    * `filter` is only used to explain an empty result: nothing matching the
    * text the user typed is a different situation from nothing being offered. */
  /** The line that opens a model's block, and the whole of it under `--short`.
    * Shared so the two listings cannot come to disagree about what a model is
    * called. */
  private def modelHeading(m: VideoModel): String =
    m.name.filter(_ != m.id).fold(m.id)(n => s"${m.id}  ($n)")

  def models(
      ms: List[VideoModel],
      filter: Option[String] = None,
      short: Boolean = false
  ): String =
    if ms.isEmpty then
      filter.fold("No video generation models available.")(f =>
        s"No video generation models match '$f'."
      )
    else if short then
      // Headings only, unseparated: the full listing runs to hundreds of lines,
      // and reading it to discover what to filter on defeats the filter.
      ms.sortBy(_.id).map(modelHeading).mkString("\n")
    else
      ms.sortBy(_.id)
        .map { m =>
          val out = List.newBuilder[String]
          out += modelHeading(m)

          def list(label: String, values: Option[List[Any]], suffix: String = ""): Unit =
            values.filter(_.nonEmpty).foreach { vs =>
              out += "  " + row(label, vs.mkString(", ") + suffix)
            }

          def field(label: String, value: Option[String]): Unit =
            value.filter(_.nonEmpty).foreach(v => out += "  " + row(label, v))

          // Every key the catalog sends is shown. A field absent or null for a
          // model prints nothing rather than an empty row, so the block stays a
          // description of that model rather than a form with blanks.
          field("slug", m.canonical_slug.filter(_ != m.id))
          field("released", m.created.map(date))
          list("durations", m.supported_durations, " s")
          list("resolutions", m.supported_resolutions)
          list("ratios", m.supported_aspect_ratios)
          list("sizes", m.supported_sizes)
          list("frames", m.supported_frame_images)
          field("audio", m.generate_audio.map(yesNo))
          field("seed", m.seed.map(yesNo))
          field("creativity", m.creativity.map(compact))
          field("upscale", m.upscale_factor.map(compact))
          field("huggingface", m.hugging_face_id)

          m.pricing_skus.filter(_.nonEmpty).foreach { p =>
            val pricing = p.toList.sortBy(_._1).map((k, v) => s"$k=${scalar(v)}").mkString(", ")
            out += "  " + row("pricing", pricing)
          }

          list("passthrough", m.allowed_passthrough_parameters)

          m.description.map(_.trim).filter(_.nonEmpty).foreach { d =>
            val oneLine = d.replaceAll("\\s+", " ")
            val clipped = if oneLine.length > 160 then oneLine.take(157) + "..." else oneLine
            out += "  " + row("about", clipped)
          }

          out.result().mkString("\n")
        }
        .mkString("\n\n")
