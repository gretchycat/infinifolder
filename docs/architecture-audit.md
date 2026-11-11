# InfiniSuite separation audit

Implementation and verification are in progress. This record is updated with
executed fresh-checkout results before delivery; it is not a declaration that
unexecuted runtime checks passed.

The current ownership and complete dependency graph are in
[architecture.md](architecture.md). Storage lifecycle/security checks are in
[storage-validation.md](storage-validation.md).

Repositories: https://github.com/gretchycat/infinifolder,
https://github.com/gretchycat/infiniscribe,
https://github.com/gretchycat/infinimedia,
https://github.com/gretchycat/infinikey-ime, and the `libinfini*` repositories
listed in the dependency graph. The reusable icon theme is
https://github.com/gretchycat/infini-icon-theme.

Confirmed corrections include sibling source dependencies, consumer inclusion of
advanced storage engines, duplicate common/image UI and icon themes, misplaced
resource contracts and document ZIP expansion, lost seek capability at the relay,
spoofable selection recipients, omitted write grants, local-only keyboard tooling
and signing, and missing reproducible source-dependency bootstrap/CI. Existing
format implementations and test cases are retained under their corrected owners.

Initial validation has built Infinifolder and InfiniKey with passing application
unit tests. Standalone library CI and the cross-library regression run are also
being executed. Device grants/playback/rendering are not yet tested: no Android
ADB device is attached. Full counts and artifact inspection results follow after
the isolated app builds.
