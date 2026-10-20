# Decision 0001: native SAF first

Date: 2026-10-19

## Context

The repository was empty and no root or scoped `AGENTS.md` existed. Phase 1 needs real Android storage access while avoiding broad storage permissions and unsafe assumptions about document IDs.

## Decision

Start with a native Kotlin Android app, minimum API 26, target API 35, and SAF tree selection. Keep provider-neutral resource identity and capability results separate from UI. Persisted SAF grants work on older Android versions; API 26 is the chosen support floor because the next transfer milestone needs notification channels for user-visible foreground work. This is a product support choice, not a SAF minimum.

## Consequences

Users explicitly grant each browsed tree; the app does not scan filesystem paths or request all-files access. SAF providers differ, so operations must report unsupported or partial results. The initial implementation uses platform Activity/view APIs to keep the skeleton dependency-light; AndroidX lifecycle and Material 3 are candidates after the baseline builds.

The restriction to SAF-only storage is superseded for primary shared storage by [Decision 0002](0002-direct-shared-storage.md), after Android’s root and Downloads picker restrictions blocked conventional file browsing.
