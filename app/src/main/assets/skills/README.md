# Bundled skills

This directory is intentionally empty in the repository.

Before building the APK, copy the SKILL.md files (and any plain-text
`scripts/` or `references/` sub-files) from the upstream Hermes repo
(`https://github.com/NousResearch/hermes-agent`, commit `2fcdbfea`, MIT licence)
into this directory, preserving each skill's folder structure.

## Suggested set (6–8 phone-sensible skills)

| Target folder inside `assets/skills/`                         | Source path in upstream repo                                              |
|---------------------------------------------------------------|---------------------------------------------------------------------------|
| `note-taking/<skill-folder>/`                                 | `skills/note-taking/<skill-folder>/SKILL.md`                             |
| `research/<skill-folder>/`                                    | `skills/research/<skill-folder>/SKILL.md`                                |
| `productivity/<skill-folder>/`                                | `skills/productivity/<skill-folder>/SKILL.md`                            |
| `software-development/codebase-inspection/`                   | `skills/software-development/codebase-inspection/SKILL.md`               |
| `software-development/simplify-code/`  *(if present)*        | `skills/software-development/simplify-code/SKILL.md`                     |
| `web/<skill-folder>/`                                         | `skills/web/<skill-folder>/SKILL.md`                                     |
| `creative/<skill-folder>/`                                    | `skills/creative/<skill-folder>/SKILL.md`                                |
| `autonomous-ai-agents/hermes-agent/`                          | `skills/autonomous-ai-agents/hermes-agent/SKILL.md`                      |

## Rules
- Do NOT bundle skills whose `platforms:` frontmatter field excludes `linux`
  and `android` (e.g. anything under `skills/apple/`).
- Keep each SKILL.md's frontmatter and licence comment intact.
- Drop any binary blobs or compiled scripts; keep only plain-text files.
- After copying, verify the app's Skills screen lists the skills correctly
  (`SkillsStore` auto-scans all directories that contain a `SKILL.md`).

## Credit
All bundled SKILL.md files are copyright Nous Research and contributors,
licensed under the MIT licence. Full licence text: https://github.com/NousResearch/hermes-agent/blob/main/LICENSE
