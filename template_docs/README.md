# GrappleMod: Skybound

[![GrappleMod: Skybound banner](./docs/media/grapplemod-skybound-banner.png)](https://github.com/weaversworkshop/grapplemod-skybound)

[![Minecraft Version](https://img.shields.io/badge/Minecraft-v${minecraft_version}-blue?style=flat-square)](https://www.minecraft.net/en-us)
[![Fabric Loader Version](https://img.shields.io/badge/Fabric_Loader-v${loader_version}-AA8554?style=flat-square)](https://fabricmc.net/use/installer/)
[![YACL Version](https://img.shields.io/badge/YACL-v${yacl_version}-pink?style=flat-square)](https://modrinth.com/mod/yacl)
[![Mod Menu Version](https://img.shields.io/badge/Mod_Menu-v${modmenu_version}-indigo?style=flat-square)](https://modrinth.com/mod/modmenu)
[![GPL-3.0](https://img.shields.io/badge/License-GNU_GPL_3.0-mint?style=flat-square)](https://www.gnu.org/licenses/gpl-3.0.en.html)

---

> ✅ **Stable branch.** This is the release branch for Minecraft 1.21.1. For in-progress work, see [`1.21.1-dev`](https://github.com/weaversworkshop/grapplemod-skybound/tree/1.21.1-dev).

# Overview

Skybound is an independent fork of [grapplemod-restitched](https://github.com/squeeglii/grapplemod-restitched)
(by CG360 / squeeglii), itself a Fabric port of [Yyon's Grappling Hook Mod](https://github.com/yyon/grapplemod).
The mod adds Grappling Hooks to Minecraft with a wide range of upgrades and customization.

This fork focuses on two things the upstream mod didn't handle well:

1. **Moving structures.** Grapple onto Create contraptions, Sable sublevels, and Create: Aeronautics airships. The rope tracks position and rotation, survives sublevel splits, and wraps around partial blocks correctly.
2. **Multiplayer.** Hook and rope state is server-authoritative — other players see exactly what you see, hooks survive relogs, and ropes can be cut with shears.

Targeting Minecraft 1.21.1 on Fabric. NeoForge support is planned.

## Features

- **Grappling Hook** with upgrades for motors, rockets, ender teleports, magnets, dual hooks, forcefields, and configurable rope styles.
- **Long Fall Boots** to land safely after a long swing.
- All upgrades apply at a vanilla **Smithing Table**.
- Built-in resource pack variants: classic textures, hook-only, no-enchants, classic recipes.
- `useLimitedHook` gamerule for servers that want to restrict where hooks can attach.
- A small set of advancements to guide new players.

## Compatibility

- **[Create](https://modrinth.com/mod/create-fabric)** — grapple onto assembled contraptions.
- **[Sable](https://modrinth.com/mod/sable)** — grapple onto sublevels (moving block-spaces).
- **[Create: Aeronautics](https://github.com/Create-Aeronautics/Create-Aeronautics)** — supported transitively via Sable.

## Reporting Issues

Found a bug? [Open an issue](https://github.com/weaversworkshop/grapplemod-skybound/issues). Include your Minecraft version, Fabric Loader version, and a list of installed mods if possible.

## 📜 Credits

See [ATTRIBUTIONS.md](/ATTRIBUTIONS.md) for full credits with attached licenses.

### Major Components

- **Original Mod** — Yyon
- **Textures** — Mayesnake
- **Forge 1.18 / 1.19 Updates** — Nyfaria
- **Fabric/Quilt Port (1.18.2+)** — CG360
- **Dynamic Physics Object Support (Create / Sable)** — weaversworkshop
- **Multiplayer Stability & Server-Authoritative State** — weaversworkshop

### Translations

- **Russian** — Blueberryy
- **French** — Neerwan
- **Brazilian Portuguese** — Eufranio

---

## Contributing

PRs and issues are welcome — please target the [`1.21.1-dev`](https://github.com/weaversworkshop/grapplemod-skybound/tree/1.21.1-dev) branch, which has the build instructions and versioning workflow.
