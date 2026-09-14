# Fingerprint timing for transcripts

This document describes the fingerprint reference format consumed by the Android app. It documents the reader contract, not a complete fingerprint generator.

## Why fingerprints are needed

Transcript and generated-chapter timestamps can belong to a reference version of an episode. The audio played on the device can differ because of dynamic ads, different edits, or a different stream/download.

The app fingerprints the audio being played and matches it against a reference fingerprint. It then builds a mapping between the two timelines:

```text
player time 125.4s -> reference time 119.8s
```

Transcript highlighting and generated chapter positioning use the reference time after this mapping is available.

The live matcher uses 8-second audio windows with a 1-second interval. It requires enough matching windows before the mapping becomes active.

## Reference file URL

The URL is not part of the fingerprint format. It is the current Android client's lookup convention:

```text
{SERVER_SHOW_NOTES_URLS}/generated_transcripts/{podcastUuid}/{episodeUuid}-fingerprints.json.gz
```

The path can be changed. The Android client and transcript service only need to agree. In the current app, the path is assembled in `FingerprintReferenceRetriever`; changing it there, or making it a build configuration value, changes the required service URL.

The response must be gzipped JSON. The decompressed body must be UTF-8 JSON.

For example, the current default path is:

```text
/generated_transcripts/podcast-123/episode-456-fingerprints.json.gz
```

A `200` response with invalid, unsupported, or empty fingerprint data is treated as unavailable. The app retries transient server failures, but `404` and `403` mean that no reference exists.

## JSON format

The Android decoder currently supports only `fingerprint-compact-v2`:

```json
{
  "format": "fingerprint-compact-v2",
  "total_duration": 3600.5,
  "checkpoint_interval": 10,
  "checkpoint_duration": 8,
  "timestamp_quantum": 1,
  "checkpoints": [
    [
      120,
      "<base64 encoded little-endian uint32 hashes>"
    ]
  ]
}
```

Fields:

| Field | Type | Meaning |
| --- | --- | --- |
| `format` | string | Must be `fingerprint-compact-v2`. |
| `total_duration` | number | Reference audio duration in seconds. |
| `checkpoint_interval` | integer | Checkpoint generation interval metadata. Keep this consistent with the generator. |
| `checkpoint_duration` | integer | Duration of each checkpoint fingerprint window in seconds. The matcher uses this value. |
| `timestamp_quantum` | integer | Seconds represented by one timestamp unit in a checkpoint delta. |
| `checkpoints` | array | Compact checkpoint entries, in chronological order. |

Each checkpoint is:

```json
[delta_timestamp_units, base64_payload]
```

The timestamp is delta-encoded. The decoder accumulates the first value from every entry and calculates:

```text
timestamp_seconds = sum(delta_timestamp_units) * timestamp_quantum
```

The base64 payload decodes to a byte array whose length is divisible by four. It contains little-endian 32-bit fingerprint hashes:

```text
uint32 hash 0, uint32 hash 1, uint32 hash 2, ...
```

The Android matcher treats these as unsigned 32-bit values. Hash ordering must match the fingerprint generator and the native matcher used by the service/tooling.

## Example checkpoint

With this metadata:

```json
{
  "timestamp_quantum": 2,
  "checkpoints": [
    [5, "AQIDBA=="],
    [3, "BQYHCA=="]
  ]
}
```

The checkpoint timestamps are `10` and `16` seconds. The first payload decodes to the little-endian bytes `01 02 03 04`, which represent one 32-bit hash.

## Adding support to a transcript service

1. Generate the reference fingerprint from the same canonical audio timeline used for the transcript.
2. Emit the `fingerprint-compact-v2` JSON shape above.
3. Gzip the UTF-8 JSON.
4. Publish it at the exact podcast/episode URL shown above.
5. Verify that the device can fetch it and that decompression produces valid JSON.
6. Test an episode containing ads or another known timeline offset, not only an episode with matching timestamps.

The service must use the same fingerprint algorithm and hash representation as the Android/native matcher. A generic audio hash will not work.

## Important app-side detail

The current Android code marks configured custom transcript URLs as directly seekable. Directly seekable transcripts use VTT timestamps directly and intentionally skip fingerprint preparation.

To use fingerprint timing for these transcripts, change that policy so they are not marked `isDirectlySeekable`, or add a separate policy that selects fingerprint timing when a matching reference file exists. Then the existing `FingerprintTimingManager` will fetch and prepare the reference automatically when the transcript is shown or playback preparation starts.

If the custom VTT was generated from exactly the same audio timeline, direct VTT timing is simpler and does not require a fingerprint file. Fingerprints are most useful when the transcript timeline and played audio can differ.
