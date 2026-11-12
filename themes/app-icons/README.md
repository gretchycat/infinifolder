# Launcher icon artwork

`icons.zip` contains the suite's small launcher base drawings and the generic
file icon symbols. `infini.svg` is the shared emblem. The Android app packages
only the generated files under `app/src/main/res/`.

Regenerate Infinifolder's launcher resources and the shared file icon artwork
from the repository root:

```sh
python3 scripts/regenerate-icons.py
```

The launcher generator defaults to Infinifolder. Other base drawings can be
rendered explicitly for review, for example:

```sh
python3 scripts/build-app-icons.py --only infinimedia
```

Those review outputs are written under the ignored
`themes/app-icons/generated/` directory. They are not required to build
Infinifolder and do not update another app repository. The committed
Infinifolder launcher resources live in `app/src/main/res/`.
