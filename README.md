# orvdo

A small CLI and library over OpenRouter's asynchronous video generation API.
upickle for JSON, requests-scala for HTTP, ZIO for the operations, decline for
the command line, os-lib for the filesystem.

## Build

Mill, with the wrapper checked in — no global install needed:

```
./mill compile
./mill run <subcommand> [options]     # the CLI, from the build
./mill assembly                       # a self-executing jar
./mill script                         # a small launcher script (see below)
./mill publishLocal                   # into ~/.ivy2/local
./mill publishMchange                 # into the mchange staging repository
```

Tests are utest, and hermetic — no API key, no network:

```
./mill test
```

`./mill run` is convenient while developing, but it decorates any non-zero exit
with a `Subprocess failed` line of its own. For actual use, `./mill assembly`
writes a launcher to `out/assembly.dest/out.jar` that runs directly and exits
with the CLI's own status:

```
out/assembly.dest/out.jar list-models -f veo
```

The examples below are written as `./mill run …`; substitute the jar freely.

## Installing

`./mill assembly` produces a single self-contained jar, which needs only a JVM.
Copy `out/assembly.dest/out.jar` somewhere on your `PATH` as `orvdo`.

`./mill script` produces a smaller alternative: a launcher that resolves the
library from a repository at run time rather than bundling it.

```
./mill script                 # writes out/script/script.dest/orvdo
cp out/script/script.dest/orvdo ~/bin/orvdo
```

```
#!/usr/bin/env -S scala-cli shebang

//> using scala "3.3.8"
//> using dep "com.mchange::orvdo:0.0.1-SNAPSHOT"

com.mchange.orvdo.Main.main(args)
```

Both versions are filled in from the build, so the script cannot drift from what
was published. It needs [scala-cli](https://scala-cli.virtuslab.org/) on your
`PATH`; the first run resolves and caches the dependency, and later runs are
quick. Exit codes and the stdout/stderr split behave exactly as they do with the
jar.

The trade-off is that the script only works where its dependency resolves.
While the version is a `-SNAPSHOT`, that means a machine where you have run
`./mill publishLocal` or `./mill publishMchange` — a snapshot is not on Maven
Central. Until a release is published, prefer the assembly jar for anyone
else's machine, or add a `//> using repository` line to
`script/orvdo.template` pointing at wherever you publish.

## Setup

```
export OPENROUTER_API_KEY=sk-or-...
```

Every subcommand declares this as a required environment variable, so it shows
up in `--help` and a missing key produces a proper usage error rather than a
stack trace.

## Usage

```
./mill run --help
./mill run list-models
./mill run check --job-id abc123
```

The full listing runs to a couple of dozen models, so `list-models` takes a
`--filter` (`-f`) that keeps only those whose id or name contains the given
text, case-insensitively:

```
./mill run list-models -f veo        # google/veo-3.1, -fast, -lite
./mill run list-models -f spacexai   # matches on the name, not the id
```

Each entry shows every field the catalog carries for that model; anything null
for a given model is omitted rather than printed blank:

```
bytedance/seedance-2.0-mini  (ByteDance: Seedance 2.0 Mini)
  slug        bytedance/seedance-2.0-mini-20260811
  released    2026-08-12
  durations   4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15 s
  resolutions 480p, 720p
  ratios      1:1, 3:4, 9:16, 4:3, 16:9, 21:9, 9:21
  sizes       480x480, 480x640, 480x854, ...
  frames      first_frame, last_frame
  audio       yes
  seed        yes
  pricing     video_tokens=0.0000035, ...
  passthrough watermark, req_key, return_last_frame
  about       Seedance 2.0 Mini is a video generation model from ByteDance. ...
```

`audio` and `seed` report whether the model *accepts* those request fields, not
what they cost — pricing is the `pricing` row's business.

Submit and print the job record immediately:

```
./mill run submit -m google/veo-3.1 -p "a duck wearing a tiny hat" -d 8 -r 1080p
```

The prompt can come from the command line with `--prompt` (`-p`), from a file
with `--prompt-file` (`-f`), or from both — in which case the file leads and the
argument follows after a blank line:

```
./mill run submit -m google/veo-3.1 -f house-style.txt -p "and at dusk"
```

That combination is the useful one: keep the considered part of a prompt in a
file under version control, and vary it from the shell. Only the combined text
is submitted, and only the combined text appears in a receipt — the split is a
convenience of the command line, not a property of the render.

`--duration`, `--resolution` and `--aspect-ratio` are checked against the
model's catalog entry before anything is sent, so a value the model does not
take is a sentence naming the ones it does rather than a 400:

```
error: unsupported settings:
  --duration 7 is not supported by google/veo-3.1; it offers 4, 6, 8
  --resolution 480p is not supported by google/veo-3.1; it offers 720p, 1080p, 4K
```

Matching ignores case and the catalog's spelling is what goes on the wire, so
`-r 4k` is sent as `4K`.

Submit, poll to completion, and save the result:

```
./mill run submit \
  -m google/veo-3.1 \
  -f prompt.txt \
  -d 8 \
  --await \
  --download-as out/clip.mp4 \
  --force
```

## Cheap by default

Left unset, the quality settings would be OpenRouter's choice — often the
model's dearest. So `submit` looks the model up in `list-models` first and fills
in the cheapest value on offer, saying so on stderr:

```
warning: filling in unset quality settings with the cheapest google/veo-3.1 offers,
         so expect the lowest quality this model produces.
  duration    4 s       (of 4, 6, 8)
  resolution  720p      (of 720p, 1080p, 4K)
  ratio       16:9      (of 16:9, 9:16)
  audio       off       (of on, off)
         Override with --duration / --resolution / --aspect-ratio /
         --generate-audio / --no-generate-audio.
```

Anything you set yourself is left alone and is not reported — the warning covers
only choices made on your behalf, so each flag you pass removes a row, and a run
that sets all four prints nothing. `--duration 8` on Veo 3.1 is the difference
between $0.80 and $1.60 a clip, so the row that disappears when you pass it is
the one you are paying for.

Because this needs the catalog, `submit` now makes a `list-models` call first,
and an unrecognised slug fails before anything is charged.

Audio is defaulted off for the same reason, since it roughly doubles the
per-second price where it is billed separately — Veo 3.1 charges
`duration_seconds_with_audio` at $0.40 against $0.20 without:

```
./mill run submit -m google/veo-3.1 -f prompt.txt --generate-audio
./mill run submit -m google/veo-3.1 -f prompt.txt --no-generate-audio
```

Either flag settles the question and drops the `audio` row;
`--no-generate-audio` exists so that asking for a silent clip on purpose is not
nagged at forever. The default is only applied to models that price audio as a
separate dimension, so it stays out of the way on the fifteen or so that do not
— including `openai/sora-2-pro`, where audio is bundled into the base rate and
there is no surprise to guard against.

## Image inputs

Pin the opening or closing frame, or supply style references:

```
./mill run submit -m google/veo-3.1 -f prompt.txt \
  --first-frame https://example.com/open.png \
  --last-frame  https://example.com/close.png \
  --reference   https://example.com/style.png
```

`--reference` is repeatable. `--first-frame` and `--last-frame` are checked
against the model's `frames` row from `list-models` — three models accept
`first_frame` only, and asking them for a last frame fails before anything is
sent.

OpenRouter fetches these itself, so they must be URLs it can reach; a local path
is rejected at parse time rather than travelling to the API to fail there.

Runway's `aleph-2` is the exception worth knowing: it lists no frames at all,
because it edits existing footage rather than generating from a still. Its
keyframe support is a provider-specific `keyframes` passthrough, reached with
`--param` (below) rather than these flags.

## Passthrough parameters

Model-specific options go through `--param key=value` (`-P`), repeatable. The
`passthrough` row of `list-models` shows what a model accepts:

```
./mill run submit -m bytedance/seedance-2.0-mini -f prompt.txt \
  -P return_last_frame=true --await --json
```

Values are read as JSON when they parse and as plain strings otherwise, so
`watermark=false` is a boolean and `negative_prompt=blurry` is a string. Force a
string that looks like JSON with shell quoting: `-P version='"3"'`.

These are not top-level request fields. OpenRouter nests them under
`provider.options.<provider-slug>.parameters`, and forwards only the block
whose slug matches the provider it routed to — a wrong slug is discarded
without complaint. The slug is not in the video catalog, so `submit` looks it
up from `/models/{id}/endpoints` and prints what it used:

```
passthrough: forwarding to provider seed
  return_last_frame true
```

A parameter absent from the model's `allowed_passthrough_parameters` is still
sent, with a warning that it may be silently dropped.

## Fetching a job later

`check` takes `--await` and `--download-as` too, so an `--await` you did not
ask for at submit time can be picked up afterwards:

```
./mill run check --job-id abc123 --await --download-as out/clip.mp4
```

That waits if the job is still running, fetches it if it is not, and saves
either way. On `submit`, `--download-as` requires `--await`, because there is
no video until the job finishes. On `check` it does not: a job that has already
completed has a URL to fetch right now. On both, `--force` requires
`--download-as`. All enforced at parse time.

`--download` takes no argument and names the files itself, from the job and the
media type the server declares:

```
./mill run submit -m google/veo-3.1 -f prompt.txt --await --download
```

```
video_B7pFInVvvptLkVDxixee.mp4
```

That is usually what you want. `--download-as` aims at a fixed name, so
re-running a command out of shell history collides with the file the *previous*
run left there — and the no-clobber machinery below, which is meant as a safety
net, fires as routine. A name carrying the job id cannot collide with a
different job.

`--download` also saves *every* output a job produced, numbering them
`video_<jobId>_0.mp4`, `video_<jobId>_1.mp4` and so on, where `--download-as`
can only name one and takes the first. Files land in the working directory.

The extension comes from the response's `Content-Type`, since the content
endpoint sends no `Content-Disposition` and there is nothing else to go on. An
unrecognised type gets `.bin` rather than a guess.

Nothing is ever overwritten without `--force`. If the target name is taken, the
file is saved beside it under a job-annotated name instead, loudly:

```
error: /Users/you/clips/duck.mp4
       already exists, so it was NOT overwritten.
       The video was saved instead as:
           /Users/you/clips/duck_B7pFInVvvptLkVDxixee.mp4
       Nothing has been lost. Pass --force to overwrite the original name.
```

The command still exits non-zero, because it did not do what you asked — but
the render is on disk, not lost. Only if that fallback name is *also* taken is
nothing written, and then the error names the `orvdo download` command that
will fetch the content. Receipts follow the same rule, and take their name from
the file that actually landed. The job details, including the content URL, are
printed before any of this either way.

Any content URL can be fetched on its own with `download`:

```
./mill run download --url "https://…/videos/abc123/content?index=0" --as out/clip.mp4
```

`--as` and `--download-as` are synonyms and exactly one is required — the short
spelling for typing, the long one for consistency with `submit` and `check`.

Content URLs are unsigned but still require your API key, so a browser or a
bare `curl` will not do. Because the command attaches that key, it refuses any
host but `openrouter.ai` and refuses plaintext `http` — a mistyped or pasted
hostile URL would otherwise be handed a working credential.

If a job returns more than one video, `--download-as` saves index 0 and names
the rest on stderr, with the command to fetch each:

```
warning: job B7pFInVv… returned 3 videos; only index 0 was saved to
         /Users/you/clips/duck.mp4. Fetch the rest with:
  orvdo download --url "https://…?index=1" --download-as "/Users/you/clips/duck-1.mp4"
```

Progress lines go to stderr; job records and model listings go to stdout, so
`./mill run check --job-id abc123 > job.txt` captures just the record.

## Receipts

How a video was made is easy to lose once the shell scrollback is gone.
`--receipt` writes it down:

```
./mill run submit -m google/veo-3.1 -f prompt.txt \
  --await --download-as out/clip.mp4 --receipt
```

```
Model       google/veo-3.1
Name        Google: Veo 3.1
Slug        google/veo-3.1-20260320
Job         B7pFInVvvptLkVDxixee
Status      completed
Generation  gen-vid-1788201304
Cost        $0.0543
Video       https://openrouter.ai/api/v1/videos/B7pFInVvvptLkVDxixee/content?index=0
Saved       /Users/you/out/clip.mp4
SHA-256     549d4d549a83f3ee8d06b9da72bcce6686cfdbd4213f337ac04699a88ae8e071

Prompt:
a duck wearing a tiny hat
```

When the receipt is written by `submit`, it also records the request:

```
Request:
  duration          4
  resolution        480p
  aspect_ratio      1:1
  generate_audio    false
  frame_images      first_frame=https://example.com/open.png
  passthrough       seed: return_last_frame=true

Prompt:
a duck wearing a tiny hat
```

The digest lets a file be checked against the record later; the request and the
prompt are the parts nothing else remembers. A completed job says nothing about
the aspect ratio or the audio setting that produced it, which is what you need
to run it again.

Where it lands, unless `--receipt-as path` says otherwise: beside the video as
`<download-as>.receipt` when one was saved, so the pair travels together;
otherwise `<model-id>-<job-id>-<timestamp>.receipt` in the working directory,
with slashes in the model id replaced so it stays one filename.

`check --receipt` works too, but a receipt written there has no model block and
no prompt — a job id says nothing about how it was asked for. `submit` without
`--await` still writes one, recording the model and prompt while they are known.

## Using it as a library

`com.mchange.orvdo.OpenRouter` is the whole API, and every operation comes in
two shapes:

```scala
OpenRouter.submit(apiKey, request)      // Task[VideoJob]
OpenRouter.rawSubmit(apiKey, request)   // Task[Raw[VideoJob]]
```

The plain form is for callers who want the parsed value and nothing else. The
`raw` form additionally carries the JSON the value was parsed from, which is
what makes it possible to notice fields OpenRouter sent that the wire types do
not model — see below. Both exist for `submit`, `check`, `listModels` and
`awaitCompletion`; `providerTags` and `download` have one form each, since
neither returns a modelled envelope.

There is no cost to the plain form: internally each operation is written once
against a `JsonWrapper[T[_]]`, instantiated at `Raw` or at `Id[T] = T`, so the
non-raw path discards the JSON rather than wrapping and unwrapping it.

Nothing below the CLI reads the environment — `apiKey` is a parameter
throughout.

## Seeing the whole response

`submit` and `check` take `--json`, which prints the response OpenRouter
actually sent rather than the formatted rows:

```
./mill run check --job-id abc123 --json
```

This matters because uPickle discards keys the wire types do not declare, which
is silent by nature. So anything discarded — from a job response or from the
catalog — is announced on stderr:

```
warning: 1 field(s) not modelled by VideoJob, and so discarded: last_frame_url
         add them to the case class to keep them.
```

The warning is on stderr and the record on stdout, so `check --job-id abc123 >
job.txt` still captures just the record. It fires under `--json` too, where it
names what the raw output would only imply.

## Notes

- `--duration`, `--resolution` and `--aspect-ratio` are validated against the
  catalog before anything is sent, so unsupported values never reach the API.
- Content URLs are unsigned and still require the bearer token, which is why
  the download path sends the `Authorization` header.
- A job that ends as `failed` or `expired` is reported, not treated as a CLI
  error; check the `Status` and `Error` rows.
