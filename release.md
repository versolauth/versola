# Release

Read this before any release work. Add a line here whenever you learn something a release must do
or check (see "Added after incidents"). The deploy itself is in [`deploy.md`](deploy.md) section 3.

Claude does not publish the release, push tags or run `versola ...` commands (`.claude/settings.json`
denies or asks for them). The operator does; Claude asks for the output.

A published tag cannot be reused, so everything under "Before the release" must hold before Publish.
If one item fails, the release does not go out.

## Before the release

- [ ] Version: the tag has no leading `v` (`0.7.1`); a `-` in it makes a pre-release.
- [ ] CI on `main` is green for the release commit (the release run itself skips tests).
- [ ] No released migration was edited: `git diff --name-status <old>..<new> -- '*/postgres/migrations'`
      shows only `A` (manual until #616 makes CI fail on it).
- [ ] New migrations work with the previous version running (additive, see
      `.claude/rules/database-migrations.md`).
- [ ] A secret was added or changed: `SecretSchema.Revision` in `scripts/gen-env.scala` is raised.
- [ ] `docker/versola-tools/release.json` is filled in for this version: `version` is the tag,
      `rollingFrom` lists the versions whose running services work on this schema (additive
      migrations, see above, and the old and new services side by side during a rollout, e.g. old auth
      against the new central's API: not "migrations only"; when in doubt leave a version out, the CLI
      then asks the operator; a version of this release's own line may be matched by a glob),
      `freshDatabaseFrom` the versions with no in-place way in (the glob syntax cannot say "below 0.7.0":
      list every earlier minor, `0.1.*` ... `0.6.*`), `notes` the release page.
      Then run `bash .github/scripts/check-release-manifest.sh <tag>` with the tag you are about to
      publish; the tag build runs the same script and a published tag cannot be reused.
- [ ] No critical or high scanner finding is open past its SLA in `SECURITY.md`.
- [ ] Release notes have a **Breaking / operator action** section, even if it says "none": rolling
      from which versions, which need a fresh database, new secrets, flags or ports, known gaps.
- [ ] Docs PRs in `versola-website` are ready, `about/changelog` updated.
- [ ] The bundled Postgres image in `docker/versola-tools/compose.fragment.vps.yml.template` is the latest
      minor of its major (tag and digest; Dependabot does not watch a `.yml.template`). Never move it to a
      new major in a release: existing data directories refuse to start until dumped and restored.

## After the release

- [ ] Images exist under the tag: `versola-tools`, `versola-auth`, `versola-central`, `versola-edge`.
- [ ] Chart `appVersion` in `k8s/*/Chart.yaml` bumped (separate PR, only after the images exist).
- [ ] Stand check by the operator: fresh install, upgrade from the previous release, restart.
- [ ] Production upgrade is a separate step, never part of the release.

## Rollback

Migrations cannot be undone; the `pg_dump` taken right before `versola migrate` is the rollback.
Steps are in `deploy.md` section 3, "Rollback".

## Added after incidents

One line each: date, what happened, what to check now.

## Report

At the end say which items were checked, which were skipped and why, and the commands you ran.
Do not mark an item you did not verify.
