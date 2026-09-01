# orvideo

A small CLI over OpenRouter's asynchronous video generation API.
upickle for JSON, requests-scala for HTTP, ZIO for the operations, decline for
the command line, os-lib for the filesystem.

## Setup

```
export OPENROUTER_API_KEY=sk-or-...
```

Every subcommand declares this as a required environment variable, so it shows
up in `--help` and a missing key produces a proper usage error rather than a
stack trace.

## Usage

```
scala-cli run . -- --help
scala-cli run . -- list-models
scala-cli run . -- check --job-id abc123
```

The full listing runs to a couple of dozen models, so `list-models` takes a
`--filter` (`-f`) that keeps only those whose id or name contains the given
text, case-insensitively:

```
scala-cli run . -- list-models -f veo        # google/veo-3.1, -fast, -lite
scala-cli run . -- list-models -f spacexai   # matches on the name, not the id
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
scala-cli run . -- submit -m google/veo-3.1 -p prompt.txt -d 8 -r 1080p -a 16:9
```

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
scala-cli run . -- submit \
  -m google/veo-3.1 \
  -p prompt.txt \
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
scala-cli run . -- submit -m google/veo-3.1 -p prompt.txt --generate-audio
scala-cli run . -- submit -m google/veo-3.1 -p prompt.txt --no-generate-audio
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
scala-cli run . -- submit -m google/veo-3.1 -p prompt.txt \
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
scala-cli run . -- submit -m bytedance/seedance-2.0-mini -p prompt.txt \
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
scala-cli run . -- check --job-id abc123 --await --download-as out/clip.mp4
```

That waits if the job is still running, fetches it if it is not, and saves
either way. On `submit`, `--download-as` requires `--await`, because there is
no video until the job finishes. On `check` it does not: a job that has already
completed has a URL to fetch right now. On both, `--force` requires
`--download-as`. All enforced at parse time. Without `--force` an existing target is left
alone and the command exits non-zero — but the job details, including the
download URL, are printed first either way.

Progress lines go to stderr; job records and model listings go to stdout, so
`scala-cli run . -- check --job-id abc123 > job.txt` captures just the record.

## Seeing the whole response

`submit` and `check` take `--json`, which prints the response OpenRouter
actually sent rather than the formatted rows:

```
scala-cli run . -- check --job-id abc123 --json
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
