package com.mchange.orvdo

import exception.*

import zio.*

/** Every operation is a `Task` that takes the API key explicitly, so nothing is
  * read from the ambient environment below the CLI layer. The HTTP calls
  * themselves are blocking (requests-scala), so they run on the blocking pool.
  */
object OpenRouter:

  val BaseUrl = "https://openrouter.ai/api/v1"

  val DefaultPollInterval: Duration = 15.seconds
  val DefaultPollTimeout: Duration = 20.minutes

  private[orvdo] type Id[T] = T

  private[orvdo] object JsonWrapper:
    given JsonWrapper[Raw] with
      def wrap[A](a : A, json : ujson.Value) : Raw[A] = Raw(a, json)
      def value[A](ta : Raw[A]) : A = ta.value
    given JsonWrapper[Id] with
      def wrap[A](a : A, json : ujson.Value) : Id[A] = a
      def value[A](ta : Id[A]) : A = ta
  private [orvdo] trait JsonWrapper[T[_]]:
    def wrap[A](a : A, json : ujson.Value) : T[A]
    def value[A](ta : T[A]) : A

  private def jsonHeaders(apiKey: String): Map[String, String] = jsonHeaders(Some(apiKey))

  /** The catalog is served to anyone, so its key is optional; every other
    * operation requires one and passes `Some`. Sending the key when we have it
    * even for the catalog, since a request that identifies itself is the better
    * citizen where rate limits are concerned. */
  private def jsonHeaders(apiKey: Option[String]): Map[String, String] =
    Map("Content-Type" -> "application/json") ++
      apiKey.map(k => "Authorization" -> s"Bearer $k")

  private def checked(r: requests.Response): requests.Response =
    if r.statusCode >= 400 then throw ApiError(r.statusCode, r.text()) else r

  private def _readJob[T[_] : JsonWrapper](r: requests.Response): T[VideoJob] =
    val json = ujson.read(checked(r).text())
    summon[JsonWrapper[T]].wrap(upickle.default.read[VideoJob](json), json)

  private def _submit[T[_] : JsonWrapper](
      apiKey: String,
      request: VideoRequest,
      provider: Option[ujson.Value] = None
  ): Task[T[VideoJob]] =
    ZIO.attemptBlocking:
      val body = VideoRequest.body(request, provider)
      _readJob[T](
        requests.post(
          s"$BaseUrl/videos",
          headers = jsonHeaders(apiKey),
          data = ujson.write(body),
          check = false,
          readTimeout = 120_000
        )
      )

  private def _check[T[_] : JsonWrapper](apiKey: String, jobId: String): Task[T[VideoJob]] =
    ZIO.attemptBlocking:
      _readJob[T](
        requests.get(
          s"$BaseUrl/videos/$jobId",
          headers = jsonHeaders(apiKey),
          check = false,
          readTimeout = 60_000
        )
      )

  /** Parse the body once into a `ujson.Value` and read the job out of that
    * tree, so the typed view and the raw view are guaranteed to describe the
    * same bytes. */
  private def rawReadJob(r: requests.Response): Raw[VideoJob] = _readJob[Raw](r)

  private def readJob(r: requests.Response) : VideoJob = _readJob[Id](r)

  private def _listModels[T[_] : JsonWrapper](apiKey: Option[String]): Task[T[VideoModels]] =
    ZIO.attemptBlocking:
      val r = requests.get(
        s"$BaseUrl/videos/models",
        headers = jsonHeaders(apiKey),
        check = false,
        readTimeout = 60_000
      )
      val json = ujson.read(checked(r).text())
      summon[JsonWrapper[T]].wrap(upickle.default.read[VideoModels](json), json)

  private def _awaitCompletion[T[_] : JsonWrapper](
      apiKey: String,
      job: T[VideoJob],
      every: Duration = DefaultPollInterval,
      timeout: Duration = DefaultPollTimeout
  )(onPoll: VideoJob => UIO[Unit]): Task[T[VideoJob]] =
    val jw = summon[JsonWrapper[T]]
    val jobId = jw.value(job).id

    // `repeat` yields the *schedule's* output, not the effect's, so the
    // schedule has to be one whose output is its input. `Schedule.spaced`
    // emits a repetition count, which is why spacing alone will not typecheck
    // here; `recurUntil` is `Schedule[Any, A, A]`, and `addDelay` spaces it.
    // The delay falls between repetitions, so the first poll is immediate and
    // no delay is served after the terminal one.
    val untilTerminal =
      Schedule.recurUntil[T[VideoJob]](r => jw.value(r).isTerminal).addDelay(_ => every)

    // A job that is already finished costs no request, and the caller gets back
    // the very value it passed rather than a re-fetched equivalent.
    if jw.value(job).isTerminal then ZIO.succeed(job)
    else
      _check[T](apiKey, jobId)
        .tap(r => onPoll(jw.value(r)))
        .repeat(untilTerminal)
        .timeoutFail( AwaitVideoTimeout(jobId, timeout) )(timeout)

  /** GET /videos/models — capabilities and pricing for every video model.
    *
    * Returns the envelope with the JSON it came from, so callers can report
    * catalog fields we do not model. Same reasoning as `readJob`. */
  def rawListModels(apiKey: Option[String] = None): Task[Raw[VideoModels]] = _listModels[Raw](apiKey)

  /** POST /videos — returns immediately with a job id and polling URL.
    *
    * `provider` is merged in as a sibling of the modelled fields rather than
    * living on `VideoRequest`, which keeps the wire type a plain case class
    * with a derived ReadWriter. */
  def rawSubmit(
      apiKey: String,
      request: VideoRequest,
      provider: Option[ujson.Value] = None
  ): Task[Raw[VideoJob]] =
    _submit[Raw](apiKey, request, provider)

  /** Poll until the job reaches a terminal state. `onPoll` sees every result. */
  def rawAwaitCompletion(
      apiKey: String,
      job: Raw[VideoJob],
      every: Duration = DefaultPollInterval,
      timeout: Duration = DefaultPollTimeout
  )(onPoll: VideoJob => UIO[Unit]): Task[Raw[VideoJob]] =
    _awaitCompletion[Raw](apiKey, job, every, timeout)(onPoll)

  /** The video catalog, which OpenRouter serves without authentication.
    *
    * Verified: the endpoint answers 200 with no `Authorization` header at all,
    * and returns byte-identical content to an authenticated request. The key is
    * therefore optional here and required nowhere else in this object. */
  def listModels(apiKey: Option[String] = None): Task[VideoModels] = _listModels[Id](apiKey)

  /** The provider slugs this model routes to, which is what
    * `provider.options` must be keyed by. Lives on the general models route,
    * not the video catalog. */
  def providerTags(apiKey: String, modelId: String): Task[List[String]] =
    ZIO.attemptBlocking:
      val r = requests.get(
        s"$BaseUrl/models/$modelId/endpoints",
        headers = jsonHeaders(apiKey),
        check = false,
        readTimeout = 60_000
      )
      upickle.default.read[EndpointsEnvelope](checked(r).text()).data.endpoints.flatMap(_.tag)

  def submit(
      apiKey: String,
      request: VideoRequest,
      provider: Option[ujson.Value] = None
  ): Task[VideoJob] =
    _submit[Id](apiKey, request, provider)

  /** GET /videos/{jobId} — current state of a job. */
  def rawCheck(apiKey: String, jobId: String): Task[Raw[VideoJob]] = _check[Raw](apiKey, jobId)

  def check(apiKey: String, jobId: String): Task[VideoJob] = _check[Id](apiKey, jobId)

  def awaitCompletion(
      apiKey: String,
      job: VideoJob,
      every: Duration = DefaultPollInterval,
      timeout: Duration = DefaultPollTimeout
  )(onPoll: VideoJob => UIO[Unit]): Task[VideoJob] =
    _awaitCompletion[Id](apiKey, job, every, timeout)(onPoll)

  /** Content URLs are unsigned, so the download carries the bearer token too. */
  def download(apiKey: String, contentUrl: String, target: os.Path, force: Boolean): Task[os.Path] =
    for
      _ <- ZIO.attemptBlocking(if os.exists(target) && !force then throw TargetExists(target))
      fetched <- fetch(apiKey, contentUrl)
      _ <- ZIO.attemptBlocking {
        os.makeDir.all(target / os.up)
        os.write.over(target, fetched.bytes)
      }
    yield target

  /** The bytes, and the media type the server declared for them.
    *
    * The type matters because a caller that did not name the file has nothing
    * else to go on: the content endpoint sends no `Content-Disposition`, so
    * there is no suggested filename to take. Splitting this out of `download`
    * costs nothing — the bytes were already read whole — and means the name
    * can be chosen after the type is known rather than guessed before. */
  def fetch(apiKey: String, contentUrl: String): Task[Fetched] =
    ZIO.attemptBlocking {
      val r = checked(
        requests.get(
          contentUrl,
          headers = Map("Authorization" -> s"Bearer $apiKey"),
          check = false,
          readTimeout = 600_000
        )
      )
      Fetched(r.bytes, r.headers.get("content-type").flatMap(_.headOption))
    }
