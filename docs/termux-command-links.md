# Termux command links

## Architecture authority and migration status

[InfiniStream architecture](infinistream_architecture_codex.md) defines the target design and takes precedence over earlier extraction proposals. [Migration checkpoints](infinistream-migration.md) records actual ownership, tested revisions and remaining work. Stream is the platform-neutral foundation; Store opens resources; Vault interprets containers only when needed; Sound, Canvas, Video and Scribe consume stream contracts. Android presentation, permissions and application composition remain adapters. Existing safety and format limits below remain requirements throughout migration. Dated evidence and old source paths describe their original revision; they are not claims that every planned extraction is complete.

In a writable folder, choose **File → New → Termux command**, enter a display
name and shell command, and optionally supply an absolute working directory.
The `.termux.iflink` file is a regular InfiniFolder link; it can be moved, backed
up and used as a home-screen shortcut. Tapping it requests a foreground Termux
terminal session. Bash receives the command as a single `-lc` argument, so normal
shell quoting, pipelines and environment expansion are available. The default
working directory is chosen by Termux.

Termux must be installed. Grant InfiniFolder **Run commands in Termux environment**
in Android app permissions, and set `allow-external-apps = true` in
`~/.termux/termux.properties`. Termux may need its display-over-other-apps
permission to show a new terminal session from the background. See the official
[Termux RUN_COMMAND documentation](https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent).
InfiniFolder cannot determine from the service request alone whether Termux has
started or completed the command; a successful request is not a completion result.

The command and working directory are stored as text in the link. Anyone with
access to the link can read them, and opening the link runs the command with the
Termux user's access. Avoid putting passwords or secrets in a link that is shared
or synchronized. Unavailable Termux installations leave the link intact.

## File associations

**Handset confirmation (2026-10-26):** The user reports that opening a Python
file in Termux appears to work after the shared-working-file handoff fix. This
confirms the observed opening path; separate write-back, source-conflict,
interruption and provider-coverage checks remain useful.

**Settings → Termux file associations** manages extension and editor-command
rules. A new installation starts with `.py → nvim`; rules can be edited or
removed. The longest matching suffix wins. Matching files opened in InfiniFolder
use the configured Termux editor when its executable has been verified. Android's **Open with → Open with Termux** can
also send a file to InfiniFolder; the receiving activity checks the saved rules
and explains when none matches. Android still chooses candidates by MIME type,
and the sender must grant access to the source URI.

InfiniFolder checks whether Termux is installed and asks Termux to run a fixed,
background `command -v` probe for each configured editor. It never runs the
configured command during this check. Simple executable names or absolute paths
followed by arguments are supported; complex shell prefixes are unverified. An
unverified editor is not used automatically. Settings shows installed, missing or
unverified and offers **Check editors**. Checking requires the Termux command
permission and its `allow-external-apps` property. Checks run when InfiniFolder
resumes and can be repeated after installing an editor.

The Android handler is disabled when Termux is unavailable or no configured editor
has been verified. Since Android registers handlers by MIME rather than by each
user-defined suffix, it may still show the generic handler for a file whose own
rule is not available; InfiniFolder checks that rule again before opening it.
The check reflects the last probe; Termux packages can change without notifying
Android, so the executable is checked again when a terminal session starts.
Android restricts package-install broadcasts while the app is stopped, so open
InfiniFolder after installing Termux to refresh the handler and editor checks.

X-plore keeps its own file associations. If the handler does not appear, its
file details provide **Compatible apps** and a MIME type setting; **Open with**
can also open a file as text. The Android filters accept typed files, file/content
URIs without a MIME type, and browsable requests. A user reported the handler
missing in X-plore before those latter filters were added; the exact request from
X-plore was not captured. The user subsequently confirmed opening through X-plore
works with the expanded filters; loading feedback received a follow-up fix.

The handoff uses the existing durable external-edit session and its bounded cache,
source fingerprints, content verification and conflict/recovery workflow. A verified
working copy is placed in InfiniFolder's shared Android media directory under
`.termux-edits/<session-id>/`, so Termux can access it. Termux may need storage
permission. This shared copy can be accessible to other apps with storage access;
the file is removed after a successful save. The filename is passed as a separate
shell argument. After the editor exits, the copy is imported and checked; only a
successful terminal result starts the normal checked write-back. A failed or
interrupted handoff keeps the source untouched and retains the shared working copy.
The session is reserved while the editor runs, including across app restarts;
**Settings → Unfinished external edits** can recover it after the editor has closed.
If Android does not deliver the completion callback, the reserved session remains
available there. A transient grant from another app can expire when the receiving
activity or process ends; in that case the session is retained and saving may
require renewed access. Broader on-device coverage remains for local/SAF/network
files, interrupted sessions, write-back and source conflicts.
