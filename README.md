# quillmark-java

> **SYNTHETIC.** Quillmark Trust & Savings is a fictional bank invented for the [Diocle](https://github.com/Nivetha200111/Diocle) hackathon project. Nothing here is derived from any real system, employer, client or person.

The Java side of the Quillmark modernization. It starts empty: [Diocle](https://github.com/Nivetha200111/Diocle) converts programs from [quillmark-mainframe](https://github.com/Nivetha200111/quillmark-mainframe) and delivers each one as a pull request:

```
programs/<PROGRAM>/
  java/              converted Java (Meta Muse Spark, repaired and certified by Diocle)
  sample.json        files, record layouts and keys, derived from the legacy copybooks
  certificate.json   pinned legacy commit, pinned verifier, held-out results, agent cost
```

## Don't trust the certificate: CI checks it

Every pull request runs **reverify** (`.github/workflows/reverify.yml`): it builds GnuCOBOL 3.2, installs the Diocle verifier from a commit on Diocle `main`, fetches the COBOL from `quillmark-mainframe` at the pinned commit, re-derives the layouts from its copybooks, and runs the original and the Java on 1,000 fresh inputs from a seed tied to the CI run. Any differing field, byte, exit code or display line fails the check. The legacy repository and the Diocle branch are pinned in the workflow, not taken from the pull request; `CODEOWNERS` requires owner review of any change to `.github/`.
