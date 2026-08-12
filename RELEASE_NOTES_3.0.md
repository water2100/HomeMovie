# HomeMovie v3.0

## Unified scraping workflow

- Individual cloud videos and cloud-folder imports now use the same persistent import-and-scrape pipeline.
- Scrape concurrency is shared across entries (2 workers by default, configurable up to 4).
- The unified Scrape Tasks page centralizes start, pause, resume, cancellation, cleanup, and task detail views.

## Playback and subtitles

- Played videos are automatically marked as watched and reflected in recent playback.
- Added daily application/playback usage statistics.
- Improved subtitle encoding compatibility and fixed Xunlei subtitle search for case-sensitive titles such as `waaa-315`.
- Added player progress-bar customization and subtitle styling previews.

## Experience and appearance

- Added a light theme and completed theme adaptation across library, details, search, favorites, cloud, task, filter-result, and log pages.
- New library entries now refresh automatically with debounced updates.
- Updated Chinese and English README documentation, including the OpenAI Codex collaboration note.
