# GrappleMod: Skybound

[![GrappleMod: Skybound banner](./docs/media/grapplemod-skybound-banner.png)](https://github.com/weaversworkshop/grapplemod-skybound)

[![Minecraft Version](https://img.shields.io/badge/Minecraft-v${minecraft_version}-blue?style=flat-square)](https://www.minecraft.net/en-us)
[![Fabric Loader Version](https://img.shields.io/badge/Fabric_Loader-v${loader_version}-AA8554?style=flat-square)](https://fabricmc.net/use/installer/)
[![YACL Version](https://img.shields.io/badge/YACL-v${yacl_version}-pink?style=flat-square)](https://modrinth.com/mod/yacl)
[![Mod Menu Version](https://img.shields.io/badge/Mod_Menu-v${modmenu_version}-indigo?style=flat-square)](https://modrinth.com/mod/modmenu)
[![GPL-3.0](https://img.shields.io/badge/License-GNU_GPL_3.0-mint?style=flat-square)](https://www.gnu.org/licenses/gpl-3.0.en.html)

---

# Project Overview

Skybound is an independent fork of [grapplemod-restitched](https://github.com/squeeglii/grapplemod-restitched)
(by CG360 / squeeglii), itself a Fabric port of [Yyon's Grappling Hook Mod (Forge)](https://github.com/yyon/grapplemod).
The mod adds Grappling Hooks to Minecraft with an assortment of items to complement them, and a wide range of
customizations.

Skybound focuses on **dynamic physics-object support**, allowing players to grapple onto Create contraptions and Sable ships.
Enhanced multiplayer features and upgraded rope physics and rendering.

Targeting Minecraft 1.21.1+ on Fabric, with NeoForge support planned.

If you encounter a problem, please [submit a bug report!](https://github.com/weaversworkshop/grapplemod-skybound/issues)

## 📜 Credits

See [ATTRIBUTIONS.md](/ATTRIBUTIONS.md) for credits with attached licenses, such as for sounds and code.
There are some smaller credits also found on [the original Forge repository!](https://github.com/yyon/grapplemod/)
which have been omitted here.

### Major Components

- **Original Mod** - Yyon
- **Textures** - Mayesnake
- **Forge 1.18 / 1.19 Updates** - Nyfaria
- **Fabric/Quilt Port (1.18.2+)** - CG360
- **Dynamic Physics Object Support (Create / Sable)** - weaversworkshop
- **Multiplayer Stability & Server-Authoritative State** - weaversworkshop

### Translations

- **Russian** - Blueberryy
- **French** - Neerwan
- **Brazilian Portuguese** - Eufranio


--- 


# Contributing

PRs and Issues are welcome! Please make a specific branch for your feature or bug-fix.


## 📦 Building/Running the project

The full project can be built with:

- `gradle clean-all bundle-compat-modules` / `gradlew clean-all bundle-compat-modules` depending on your install.
  - This should build to `/build/` in the root project with all the compatibility extensions bundled in
- If you want to just build the core mod, `gradle clean-all :Core:build collect-jars` / `gradlew clean-all :Core:build collect-jars`
  - This should build to `/build/` in the root project using the original build behaviour.

If running the mod in a dev environment, runs should be created automatically. If not, consult the
[Fabric Loom Wiki](https://fabricmc.net/wiki/documentation:fabric_loom) on how to generate these through gradle.

Once the runs are generated, running them should place the environments for each in the following isolated folders:

- Client `[sub project]/run/client/`
- Server `[sub project]/run/server/`

*Note that runs are only generated for the Core project - See ideConfigGenerated on the loom wiki for how to enable 
generation on other subprojects.*



## 📈 Updating Versions / Adding Dependencies

> Note: Configs intentionally don't work outside of release versions due to a lack of
> YACL support. There is a warning in-game for this.

A lot of this project is streamlined to make version updates quicker by reducing the amount of redundant version
strings. All mod dependencies should have their versions listed in the `gradle.properties` file, using variables
to drop them into files such as `fabric.mod.json` & this README when needed. Minecraft & Fabric versions are handled in
the exact same way for the same reasons.


### For Minecraft Version Updates:

- Check [the Fabric Develop utility](https://fabricmc.net/develop/) to get the version strings for a version
    - do NOT use `yarn_mappings` -- this project uses Mojmaps
- Copy the versions found into the appropriate entries found in `gradle.properties`
    - `minecraft_long_version` is the same as `minecraft_version` for __release__ versions and __snapshot__ versions
    - For __pre-releases__ and __release candidates__, they should have an extra dot (`1.20-pre1` -> `1.20-pre.1`)
    - This is because the loaded Fabric dependency and the mappings are named with slightly different schemes. :(
- Run `gradle updateDocTemplates` / `gradlew updateDocTemplates` to update any documentation that lists versions


### For Updating Dependencies:

- Change the dependency version found in `gradle.properties`
- Run `gradle updateDocTemplates` / `gradlew updateDocTemplates` to update any documentation that lists versions


### For New Dependencies:

- Add a new entry to `gradle.properties` with the dependency's version.
- Add the dependency inside `build.gradle`, using a project placeholder referencing the `gradle.properties` property
- Add the dependency to the `fabric.mod.json`, using a placeholder referencing the `gradle.properties` property
- Add a new badge to `/template_docs/README.md`, using a placeholder referencing the `gradle.properties` property
- Run `gradle updateDocTemplates` / `gradlew updateDocTemplates` to update any documentation that lists versions

---