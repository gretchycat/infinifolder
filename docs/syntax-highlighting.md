# Syntax highlighting and text appearance

Scribe owns portable lexical tokenization, UTF-16 scope ranges, style inheritance
and JSON descriptors. The shared Android adapter owns spans, font resolution,
preferences, source pickers and layout anchors. Text viewing/editing and
HTML/Markdown source viewing/editing use the same controller; rendered previews
keep their existing rendering. Styling never changes source characters or save
bytes and does not enable editing of partially loaded documents.

## Controls

In the text viewer choose **Aa → Text appearance**; Encoding is in the same menu.
HTML/Markdown provide **Aa** on their toolbar. Choose automatic language detection,
a language override or Plain; Forest dark/Paper light; default text or token styles.
Colors, font family, size, bold/italic/underline/strikethrough can be customized.
Blank token properties inherit. Family names are resolved by the platform, with
its normal fallback if unavailable; font files are not bundled by a descriptor.

Import/export uses a self-contained JSON theme. Preferences are shared by the three
source viewers and language overrides are keyed by display name. Import errors
leave the previous theme active. See [samples](../filesystem-test-images/syntax/README.md).

## Descriptor conventions

We use [TextMate scopes and VS Code tokenColors](https://code.visualstudio.com/api/language-extensions/syntax-highlight-guide),
separating tokens from appearance. This checkpoint implements bounded lexical
profiles; it is **not** a TextMate/Oniguruma grammar interpreter, semantic language
server or VS Code extension host. Arbitrary grammar packages cannot be imported.

The [schema](../libraries/libinfiniscribe/schema/text-theme.schema.json) and
[example](../libraries/libinfiniscribe/examples/custom-text-theme.json) define the
supported theme subset:

- `colors.editor.foreground` / `colors.editor.background` set defaults.
- `tokenColors[].scope` accepts dotted scopes, descendant chains, comma-separated
  selectors or arrays. Dotted parent scopes match descendants; more specific rules
  override declared properties and later equal-specificity rules win.
- `settings.foreground`, `background`, and `fontStyle` follow TextMate conventions.
  `fontStyle` is a whitespace-separated list of bold/italic/underline/strikethrough;
  an empty string clears inherited emphasis. Colors use #RGB/#RGBA/#RRGGBB/#RRGGBBAA.
- The namespaced `infiniscribe` extension supplies `fontFamily` and `fontSize`, globally
  or inside a rule's settings. Global version is 1; sizes are 6–96 logical scalable
  units (Android sp). Optional boolean emphasis fields retain partial inheritance
  when our typed descriptors are exported. Other platforms can resolve the same
  logical styles through their own adapters.
- External `include` files, selector exclusions/wildcards/group expressions and
  external `tokenColors` references are rejected explicitly. Unused UI/semantic
  theme keys do not add capabilities. Descriptors are limited to 256 KiB/512 rules.

## Source-file identity

Store supplies explicit source MIME associations rather than depending on the
Android device's extension database. The editable MIME/icon registry, listing
identity resolver and generated Android Open-with registrations use these same
associations. Generic provider `text/plain` metadata is refined by a known suffix;
saved user associations and icon choices retain their existing precedence.

| Source | Default MIME |
| --- | --- |
| `.c`, `.h` | `text/x-csrc`, `text/x-chdr` |
| `.cpp`/`.cc`/`.cxx`, `.hpp`/`.hh`/`.hxx` | `text/x-c++src`, `text/x-c++hdr` |
| `.pas`/`.pp` | `text/x-pascal` |
| `.ada`/`.adb`/`.ads` | `text/x-adasrc` |
| `.bas`/`.bi` | `text/x-basic` |
| `.bat`/`.cmd` | `application/x-bat` |

These are conventional identities, including types from the
[freedesktop shared MIME database](https://gitlab.freedesktop.org/xdg/shared-mime-info).
The expanded associations also cover Java/C#/Kotlin/Scala, Python/Ruby/Perl/PHP,
JavaScript/TypeScript, Rust/Go/Swift/Dart, shells/PowerShell, Fortran/COBOL,
assembly, Lisp/Scheme/Clojure, Haskell/Erlang/Elixir/F#, Julia/Nim, web styles,
SQL, XML schemas, build scripts and patches. Scripts open as editable text.
Association does not add an interpreter or compiler.

The bundled theme uses the existing code artwork and extension label for these
files. A theme can provide a separate icon for each MIME. Headers default to C
unless their suffix identifies C++; ambiguous language preferences can be changed
in the MIME registry. Existing case-insensitive extension matching is retained
(`.C` therefore follows `.c`). The complete associations are in
[supported-types.csv](supported-types.csv).

Specific MIME identity is separate from syntax coloring. Only the lexical profiles
listed below are implemented; Pascal, Ada, BASIC and other additional languages
remain readable/editable without a dedicated highlighter. Existing profiles now
also recognize alternate C++/Python/TypeScript suffixes and source MIME types when
the provider supplies no useful filename.

## Bounds and context

Language detection uses extension, MIME and an initial shebang. Profiles cover
JSON, JavaScript/TypeScript, Java/Kotlin/C/C++/C#, Python, shell, SQL, XML/HTML,
CSS, YAML/TOML/INI and Markdown. These are lexical colorizers, not validators;
interpolation, embedded languages and advanced dialects are not fully parsed.

The controller debounces edits/scrolls, snapshots at most 65,536 UTF-16 code units
(up to 128 KiB of raw UTF-16 payload) around the viewport, checks generations and cancellation, and retains at most
8,192 syntax spans. It removes only its own spans; search/selection/IME spans and
undo/save data remain separate. Layout anchors are restored after metric changes,
and text line labels use the actual styled/wrapped layout baselines.

Scribe exposes continuation state for strings/comments/tags. The Android adapter
uses bounded local context; a window beginning inside a construct opened farther
back can have provisional coloring, including after a distant random-access jump.
It does not scan an entire large source to establish syntax state. Budget overflow
leaves remaining text readable with default styling. Exact whole-file grammar
state and embedded-language engines can be added behind the shared descriptor.

Gson 2.11.0 supplies JSON handling; its Apache 2.0 license/notice are bundled under
META-INF/libinfiniscribe. Original project licensing remains unchanged.
