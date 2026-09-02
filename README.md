# orvdo

A small CLI and library over OpenRouter's asynchronous video generation API.
upickle for JSON, requests-scala for HTTP, ZIO for the operations, decline for
the command line, os-lib for the filesystem.

## Quickstart

### Prerequisites

You should have [scala-cli](https://scala-cli.virtuslab.org/) [installed](https://scala-cli.virtuslab.org/install) on your machine.

You really should.

### Installation

Download the `orvdo` script.

You'll find it as a downloadable binary in the [latest release](https://github.com/swaldman/orvdo/releases/latest).
Make sure that it's executable:

```plaintext
chmod +x orvdo
```

and place it in a directory on your `PATH`.

### List available models

```plaintext
orvdo list-models -s
```

the `-s` is short for `--short`. If you leave it out, you'll see... a lot.

```plaintext
% orvdo list-models -s
alibaba/happyhorse-1.0  (Alibaba: HappyHorse 1.0)
alibaba/happyhorse-1.1  (Alibaba: HappyHorse 1.1)
alibaba/wan-2.6  (Alibaba: Wan 2.6)
alibaba/wan-2.7  (Alibaba: Wan 2.7)
alibaba/wan-3.0  (Alibaba: Wan 3.0)
alibaba/wan-3.0-prime  (Alibaba: Wan 3.0 Prime)
black-forest-labs/flux-3-video  (Black Forest Labs: FLUX.3 Video)
black-forest-labs/flux-video-upscale  (Black Forest Labs: FLUX Video Upscale)
bytedance/seedance-1-5-pro  (ByteDance: Seedance 1.5 Pro)
bytedance/seedance-2.0  (ByteDance: Seedance 2.0)
bytedance/seedance-2.0-fast  (ByteDance: Seedance 2.0 Fast)
bytedance/seedance-2.0-mini  (ByteDance: Seedance 2.0 Mini)
bytedance/seedance-2.5  (ByteDance: Seedance 2.5)
google/veo-3.1  (Google: Veo 3.1)
google/veo-3.1-fast  (Google: Veo 3.1 Fast)
google/veo-3.1-lite  (Google: Veo 3.1 Lite)
heygen/avatar-iv  (HeyGen: Avatar IV)
kwaivgi/kling-v3.0-pro  (Kling: Video v3.0 Pro)
kwaivgi/kling-v3.0-std  (Kling: Video v3.0 Standard)
kwaivgi/kling-video-o1  (Kling: Video O1)
minimax/hailuo-2.3  (MiniMax: Hailuo 2.3)
minimax/hailuo-3  (MiniMax: H3)
minimax/hailuo-3-max  (MiniMax: H3 Max)
openai/sora-2-pro  (OpenAI: Sora 2 Pro)
runway/aleph-2  (Runway: Aleph 2.0)
runway/gen-4.5  (Runway: Gen-4.5)
x-ai/grok-imagine-video  (SpaceXAI: Grok Imagine Video)
x-ai/grok-imagine-video-1.5  (SpaceXAI: Grok Imagine Video 1.5)
```

Pick a model you like, and filter on it to learn about it. (Leave off the -s, you want all the info!)

```plaintext
% orvdo list-models -f bytedance/seedance-2.0-mini
bytedance/seedance-2.0-mini  (ByteDance: Seedance 2.0 Mini)
  slug        bytedance/seedance-2.0-mini-20260811
  released    2026-08-12
  durations   4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15 s
  resolutions 480p, 720p
  ratios      1:1, 3:4, 9:16, 4:3, 16:9, 21:9, 9:21
  sizes       480x480, 480x640, 480x854, 640x480, 854x480, 1120x480, 720x720, 720x960, 720x1280, 720x1680, 960x720, 1280x720, 1680x720
  frames      first_frame, last_frame
  audio       yes
  seed        yes
  pricing     video_tokens=0.0000035, video_tokens_with_video_input=0.0000021, video_tokens_without_audio=0.0000035
  passthrough watermark, req_key, return_last_frame
  about       Seedance 2.0 Mini is a video generation model from ByteDance. It supports text-to-video, image-to-video with first and last frame control, and multimodal ref...
```

For more on the `list-models` subcommand, see the help:

```plaintext
orvdo list-models --help
```

### Run a model

In order to run a model, `orvdo` will require an API key. You gotta pay to generate video on [openrouter](https://openrouter.ai), alas.

Once you have it, export it into your environment as OPENROUTER_API_KEY:

```plaintext
export OPENROUTER_API_KEY=<your-secret-key-here>
```

Then it's just as simple as...

```plaintext
% orvdo run --model bytedance/seedance-2.0-mini --prompt "An android dreams electric sheep."
warning: filling in unset quality settings with the cheapest bytedance/seedance-2.0-mini offers,
         so expect the lowest quality this model produces.
  duration    4 s       (of 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15)
  resolution  480p      (of 480p, 720p)
  ratio       1:1       (of 1:1, 3:4, 9:16, 4:3, 16:9, 21:9, 9:21)
  audio       off       (of on, off)
         Override with --duration / --resolution / --aspect-ratio /
         --generate-audio / --no-generate-audio.
submitted i3cl3HBsrhssEAMtNUks, waiting for completion...
  pending
  pending
  pending
  pending
  pending
  pending
  pending
  completed
Job         i3cl3HBsrhssEAMtNUks
Status      completed
Generation  gen-vid-1788368496-uvBwUzVzqCFqPD2lXk3D
Cost        $0.0543
Poll        https://openrouter.ai/api/v1/videos/i3cl3HBsrhssEAMtNUks
Video       https://openrouter.ai/api/v1/videos/i3cl3HBsrhssEAMtNUks/content?index=0
Saved       /Users/swaldman/tmp/video_20260902T170327Z_i3cl3HBsrhssEAMtNUks.mp4
Receipt     /Users/swaldman/tmp/video_20260902T170327Z_i3cl3HBsrhssEAMtNUks.mp4.receipt
```

Check out your new mp4 file, `video_20260902T170327Z_i3cl3HBsrhssEAMtNUks.mp4`:

https://github.com/user-attachments/assets/9da1b1fb-3f00-4834-9453-06ac73afc2ba

Please note that since we supplied no `--duration`, `--resolution`, `--aspect-ratio`,
or preferences about audio, the script defaulted to the cheapest available options.
Use `orvdo list-models` for information about what options would be available for each model.

Also check out the `.mp4.receipt`. It's a text file that keeps a useful record of what you've done.

### Go deeper

Try

```plaintext
orvdo run --help
```

Or, for more control, check out...

```plaintext
orvdo --help
```

and 

```plaintext
orvdo submit --help
```

Or, just keep reading below!

## Setup

```
export OPENROUTER_API_KEY=sk-or-...
```

Every subcommand that talks to your account declares this as a required
environment variable, so it shows up in `--help` and a missing key produces a
proper usage error rather than a stack trace.

`list-models` is the exception: the model catalog is public, so browsing it
needs no key and no account. The key is still sent if the variable happens to
be set.

## Usage

The examples below are written as `orvdo …`, as they would be typed with the
script or the jar on your `PATH`. From a source checkout without either,
`./mill run …` is the equivalent.

```
orvdo --help
orvdo list-models
orvdo check --job-id abc123
```

The full listing runs to a couple of dozen models and several hundred lines, so
`--short` (`-s`) gives one line per model — the id and the name, nothing else:

```
orvdo list-models --short
```

```
alibaba/wan-3.0  (Alibaba: Wan 3.0)
black-forest-labs/flux-3-video  (Black Forest Labs: FLUX.3 Video)
bytedance/seedance-2.0-mini  (ByteDance: Seedance 2.0 Mini)
google/veo-3.1  (Google: Veo 3.1)
```

That is the usual way in: skim the catalog, then narrow. `--filter` (`-f`)
keeps only models whose id or name contains the given text, case-insensitively,
and combines with `--short`:

```
orvdo list-models -f veo        # google/veo-3.1, -fast, -lite
orvdo list-models -f spacexai   # matches on the name, not the id
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

Most of the time you want one command:

```
orvdo run -m google/veo-3.1 -p "a duck wearing a tiny hat"
```

`run` submits, waits for the render, saves the video under a name derived from
the job, and writes a receipt beside it — `submit --await --download --receipt`
without the ceremony. It takes every generation option `submit` does
(`--duration`, `--resolution`, `--aspect-ratio`, the audio flags, the image
inputs, `--json`, `--param`), and none of the output-side ones, which it has
already decided for you. Use `submit` when you want that control: to fire and
forget without waiting, to name the file yourself, or to overwrite with
`--force`.

Submit and print the job record immediately:

```
orvdo submit -m google/veo-3.1 -p "a duck wearing a tiny hat" -d 8 -r 1080p
```

The prompt can come from the command line with `--prompt` (`-p`), from a file
with `--prompt-file` (`-f`), or from both — in which case the file leads and the
argument follows after a blank line:

```
orvdo submit -m google/veo-3.1 -f house-style.txt -p "and at dusk"
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
orvdo submit \
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
orvdo submit -m google/veo-3.1 -f prompt.txt --generate-audio
orvdo submit -m google/veo-3.1 -f prompt.txt --no-generate-audio
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
orvdo submit -m google/veo-3.1 -f prompt.txt \
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
orvdo submit -m bytedance/seedance-2.0-mini -f prompt.txt \
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
orvdo check --job-id abc123 --await --download-as out/clip.mp4
```

That waits if the job is still running, fetches it if it is not, and saves
either way. On `submit`, `--download-as` requires `--await`, because there is
no video until the job finishes. On `check` it does not: a job that has already
completed has a URL to fetch right now. On both, `--force` requires
`--download-as`. All enforced at parse time.

`--download` takes no argument and names the files itself, from the job and the
media type the server declares:

```
orvdo submit -m google/veo-3.1 -f prompt.txt --await --download
```

```
video_20260902T150233Z_B7pFInVvvptLkVDxixee.mp4
```

That is usually what you want. `--download-as` aims at a fixed name, so
re-running a command out of shell history collides with the file the *previous*
run left there — and the no-clobber machinery below, which is meant as a safety
net, fires as routine. A name carrying the job id cannot collide with a
different job.

The timestamp leads because job ids carry no order of their own, and clips are
often generated to be joined in sequence — pulling the last frame of one to open
the next. A UTC timestamp in this format sorts lexicographically into
chronological order, so plain `ls` reconstructs the sequence:

```
video_20260902T140312Z_zQ7bK2mXpL.mp4
video_20260902T141847Z_aB3nR9tYuI.mp4
video_20260902T143001Z_M5wE1cVdOs.mp4
```

It is UTC rather than local time precisely so the ordering survives time zones
and daylight saving; the date in a filename may not be your date.

`--download` also saves *every* output a job produced, numbering them
`video_<timestamp>_<jobId>_0.mp4`, `_1.mp4` and so on, where `--download-as`
can only name one and takes the first. Every output of a job shares one
timestamp, so they group. Files land in the working directory.

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
orvdo download --url "https://…/videos/abc123/content?index=0" --as out/clip.mp4
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
`orvdo check --job-id abc123 > job.txt` captures just the record.

## Receipts

How a video was made is easy to lose once the shell scrollback is gone.
`--receipt` writes it down:

```
orvdo submit -m google/veo-3.1 -f prompt.txt \
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

## Seeing the whole response

`submit` and `check` take `--json`, which prints the response OpenRouter
actually sent rather than the formatted rows:

```
orvdo check --job-id abc123 --json
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

## Building from source

Most people want the `orvdo` script from the [latest
release](https://github.com/swaldman/orvdo/releases/latest) — see the Quickstart
above. This section is for building it yourself.

Mill, with the wrapper checked in — no global install needed:

```
./mill compile
./mill run <subcommand> [options]     # the CLI, from the build
./mill assembly                       # a self-executing jar
./mill script                         # a small launcher script
./mill publishLocal                   # into ~/.ivy2/local
```

Tests are utest, and hermetic — no API key, no network:

```
./mill test
```

`./mill run` is convenient while developing, but it decorates any non-zero exit
with a `Subprocess failed` line of its own, so it is not how you would install
the tool.

### A self-contained jar

`./mill assembly` produces a single jar that needs only a JVM, runs directly and
exits with the CLI's own status. Copy it somewhere on your `PATH` as `orvdo`:

```
./mill assembly
cp out/assembly.dest/out.jar ~/bin/orvdo
```

### A launcher script

`./mill script` produces a smaller alternative — the same thing the releases
carry: a launcher that resolves the library at run time rather than bundling it.

```
./mill script                 # writes out/script/script.dest/orvdo
cp out/script/script.dest/orvdo ~/bin/orvdo
```

```
#!/usr/bin/env -S scala-cli shebang

//> using scala "3.3.8"
//> using dep "com.mchange::orvdo:0.0.1"

com.mchange.orvdo.Main.main(args)
```

Both versions are filled in from the build, so the script cannot drift from what
was published. It needs [scala-cli](https://scala-cli.virtuslab.org/) on your
`PATH`; the first run resolves and caches the dependency, and later runs are
quick. Exit codes and the stdout/stderr split behave exactly as they do with the
jar.

The trade-off is that the script only works where its dependency resolves. Once
a version is published to Maven Central that is anywhere; until then, or for a
version published only with `./mill publishLocal`, it
is a machine that has it locally. For a version that is not on Central, prefer
the assembly jar, or add a `//> using repository` line to
`script/orvdo.template` pointing at wherever you publish.

## Notes

- `--duration`, `--resolution` and `--aspect-ratio` are validated against the
  catalog before anything is sent, so unsupported values never reach the API.
- Content URLs are unsigned and still require the bearer token, which is why
  the download path sends the `Authorization` header.
- A job that ends as `failed` or `expired` is reported, not treated as a CLI
  error; check the `Status` and `Error` rows.
