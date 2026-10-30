# Desktop icon themes

## Architecture authority and migration status

[InfiniStream architecture](infinistream_architecture_codex.md) defines the target design and takes precedence over earlier extraction proposals. [Migration checkpoints](infinistream-migration.md) records actual ownership, tested revisions and remaining work. Stream is the platform-neutral foundation; Store opens resources; Vault interprets containers only when needed; Sound, Canvas, Video and Scribe consume stream contracts. Android presentation, permissions and application composition remain adapters. Existing safety and format limits below remain requirements throughout migration. Dated evidence and old source paths describe their original revision; they are not claims that every planned extraction is complete.

The icon theme chooser prepares the bundled themes on first use, off the UI thread. Select **Icon theme…** in the file manager menu, choose a theme, and apply it. Imported themes can still be selected or installed from a ZIP or TAR archive. File previews and explicit custom icons take precedence where applicable.

The bundled archives contain the desktop themes' places, MIME type and device icons needed by the file manager, rather than their complete desktop application and panel icon sets. Symbolic links in the original packages are stored as copies so that icons work in app-private storage. Missing icons fall back to inherited themes and ultimately the app's default icons.

- GNOME Adwaita 50.0 and Adwaita Legacy 46.2: <https://download.gnome.org/sources/adwaita-icon-theme/50/> and <https://download.gnome.org/sources/adwaita-icon-theme-legacy/46/>. The packaged icon data and indexes come from the corresponding installed Termux packages.
- KDE Breeze and Breeze Dark 6.30.0: <https://download.kde.org/stable/frameworks/6.30/>. The packaged icon data and indexes come from the installed Termux package.
- Community Papirus 20260801: <https://github.com/PapirusDevelopmentTeam/papirus-icon-theme/releases/tag/20260801>. The bundled archive contains its 16- and 48-pixel places, MIME type and device icons. It inherits Breeze.

Their license notices are in [licenses/icon-themes](licenses/icon-themes/). A Tela 2026-07-07 source archive was downloaded for compatibility investigation; Tela's source tree requires its installer to assemble a theme and is not included as a selectable theme.
