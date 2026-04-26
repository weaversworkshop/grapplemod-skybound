# Attributions

## 💻 Source

__**Skybound**__ (this project)
- Upstream: [grapplemod-restitched](https://github.com/squeeglii/grapplemod-restitched) by CG360 / squeeglii
- [License @ Fork Point](https://github.com/squeeglii/grapplemod-restitched/blob/303118f338609bfe47fe3cdd2212e36ba18c4cd6/LICENSE)
- Changes by: weaversworkshop
- Licensed under the GNU General Public License v3.0
- Change summary as required by the license:
    - Added dynamic physics-object support
        - Grappling onto Create contraption entities
        - Grappling onto Sable sublevels (including in-flight, rotating, and split/merge cases)
        - Multi-space raycasting for rope wrap across plot space ↔ world space
    - Multiplayer stability and server-authoritative state
        - Server is the single source of truth for hook / rope / attachment state
        - Observer rope sync after attachment
        - Hook persistence across player relog
        - Fixes for hook-on-hook attachment, mob hook visuals, and reattach halt loops
    - Removed broken Gliders mod compat
    - + More as this document won't get updated regularly


__**grapplemod-restitched**__
- Upstream: [Grappling Hook Mod](https://github.com/yyon/grapplemod) by Yyon (Forge)
- Changes by: CG360 / squeeglii
- Licensed under the GNU General Public License v3.0
- Change summary as required by the license:
    - Support for versions 1.18.x through 1.21.1
    - Port to the Fabric toolchain involving
      - Modifying how network messages are sent
      - Replacing old registries with newer, more modular structures
      - Replacing Forge Hooks with Mixins (and occasional fabric hooks)
    - Refactors including
      - Package restructuring
      - Using modern Java 17 features where applicable
      - Renaming variables and classes for clarity + to adhere to proper camel case
      - Unpacking nested classes for readability
    - Rewriting components like
      - Templates (Creative Menu Hooks, but it's now a survival feature)
      - Customizations (Now registry-backed)
    - Added new features including
      - Template Table & Blueprints
      - Style Customizations
      - Advancements
    - + More as this document won't get updated regularly


__**Grappling Hook Mod**__ (original)
- Created by: Yyon (see main README for additional credits)
- [License @ Restitched Fork Point](https://github.com/yyon/grapplemod/blob/61dd2af68a403dc9f6dbb6e659bfffa4b9bbad5f/COPYING)
- Copyright (C) Yyon, licensed under the GNU General Public License v3.0


## 🔌 Compatibility Targets

Skybound ships compatibility modules that link against the following mods. Their code is not redistributed
here — these are runtime integrations only. Please support the original authors.

__**Create**__
- by simibubi & the Create team
- https://github.com/Creators-of-Create/Create
- Used by the `Compat/Create` module to support grappling onto Create contraption entities.

__**Sable**__
- Project owner: [ryanhcode](https://www.curseforge.com/members/ryanhcode/projects)
- Contributors: Eriksonn, Ocelot, Cyvack, BeeIsYou, KyanBirb, Cake, Rhyguy1, and the Dimforge
  maintainers (Rapier physics engine)
- https://modrinth.com/mod/sable
- Licensed under the PolyForm Shield License 1.0.0 — Skybound integrates with Sable at runtime only
  and does not redistribute its code.
- Used by the `Compat/Sable` module to support grappling onto Sable sublevels (and, transitively, Create
  Aeronautics airships, which ride on Sable).


## 🎵 Sound Effects

__**Ender Staff Sounds**__

- Outroelison (Modified by Yyon) 
- https://freesound.org/people/outroelison/sounds/150950/
- Copyright (C) outroelison 2012 under the CC0 1.0 Universal License