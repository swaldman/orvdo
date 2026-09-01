package orvideo

import zio.*

/** Every operation is a `Task` that takes the API key explicitly, so nothing is
  * read from the ambient environment below the CLI layer. The HTTP calls
  * themselves are blocking (requests-scala), so they run on the blocking pool.
  */
object OpenRouter:

  val BaseUrl = "https://openrouter.ai/api/v1"

  val DefaultPollInterval: Duration = 15.seconds
  val DefaultPollTimeout: Duration = 20.minutes

  final case class ApiError(status: Int, body: String)
      extends RuntimeException(s"OpenRouter returned HTTP $status: ${body.take(500)}")

  final case class TargetExists(path: os.Path)
      extends RuntimeException(s"$path already exists; pass --force to overwrite it")

  private def jsonHeaders(apiKey: String): Map[String, String] =
    Map("Authorization" -> s"Bearer $apiKey", "Content-Type" -> "application/json")

  private def checked(r: requests.Response): requests.Response =
    if r.statusCode >= 400 then throw ApiError(r.statusCode, r.text()) else r

  /** Parse the body once into a `ujson.Value` and read the job out of that
    * tree, so the typed view and the raw view are guaranteed to describe the
    * same bytes. */
  private def readJob(r: requests.Response): Raw[VideoJob] =
    val json = ujson.read(checked(r).text())
    Raw(upickle.default.read[VideoJob](json), json)

  /** GET /videos/models — capabilities and pricing for every video model.
    *
    * Returns the envelope with the JSON it came from, so callers can report
    * catalog fields we do not model. Same reasoning as `readJob`. */
  def listModels(apiKey: String): Task[Raw[VideoModels]] =
    ZIO.attemptBlocking {
      val r = requests.get(
        s"$BaseUrl/videos/models",
        headers = jsonHeaders(apiKey),
        check = false,
        readTimeout = 60_000
      )
      val json = ujson.read(checked(r).text())
      Raw(upickle.default.read[VideoModels](json), json)
    }

  /** The provider slugs this model routes to, which is what
    * `provider.options` must be keyed by. Lives on the general models route,
    * not the video catalog. */
  def providerTags(apiKey: String, modelId: String): Task[List[String]] =
    ZIO.attemptBlocking {
      val r = requests.get(
        s"$BaseUrl/models/$modelId/endpoints",
        headers = jsonHeaders(apiKey),
        check = false,
        readTimeout = 60_000
      )
      upickle.default.read[EndpointsEnvelope](checked(r).text()).data.endpoints.flatMap(_.tag)
    }

  /** POST /videos — returns immediately with a job id and polling URL.
    *
    * `provider` is merged in as a sibling of the modelled fields rather than
    * living on `VideoRequest`, which keeps the wire type a plain case class
    * with a derived ReadWriter. */
  def submit(
      apiKey: String,
      request: VideoRequest,
      provider: Option[ujson.Value] = None
  ): Task[Raw[VideoJob]] =
    ZIO.attemptBlocking {
      val body = upickle.default.writeJs(request)
      provider.foreach(p => body.obj("provider") = p)
      readJob(
        requests.post(
          s"$BaseUrl/videos",
          headers = jsonHeaders(apiKey),
          data = ujson.write(body),
          check = false,
          readTimeout = 120_000
        )
      )
    }

  /** GET /videos/{jobId} — current state of a job. */
  def check(apiKey: String, jobId: String): Task[Raw[VideoJob]] =
    ZIO.attemptBlocking {
      readJob(
        requests.get(
          s"$BaseUrl/videos/$jobId",
          headers = jsonHeaders(apiKey),
          check = false,
          readTimeout = 60_000
        )
      )
    }

  /** Poll until the job reaches a terminal state. `onPoll` sees every result. */
  def awaitCompletion(
      apiKey: String,
      job: Raw[VideoJob],
      every: Duration = DefaultPollInterval,
      timeout: Duration = DefaultPollTimeout
  )(onPoll: VideoJob => UIO[Unit]): Task[Raw[VideoJob]] =
    def loop(current: Raw[VideoJob]): Task[Raw[VideoJob]] =
      if current.value.isTerminal then ZIO.succeed(current)
      else ZIO.sleep(every) *> check(apiKey, current.value.id).tap(r => onPoll(r.value)).flatMap(loop)

    loop(job).timeoutFail(
      new RuntimeException(s"job ${job.value.id} did not finish within ${timeout.render}")
    )(timeout)

  /** Content URLs are unsigned, so the download carries the bearer token too. */
  def download(apiKey: String, contentUrl: String, target: os.Path, force: Boolean): Task[os.Path] =
    ZIO.attemptBlocking {
      if os.exists(target) && !force then throw TargetExists(target)
      os.makeDir.all(target / os.up)
      val r = requests.get(
        contentUrl,
        headers = Map("Authorization" -> s"Bearer $apiKey"),
        check = false,
        readTimeout = 600_000
      )
      os.write.over(target, checked(r).bytes)
      target
    }
