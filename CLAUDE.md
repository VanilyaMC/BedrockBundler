# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

BedrockBundler is a beta Paper plugin plus Geyser extension that makes Nexo and ItemsAdder content (items, 3D models, blocks, armor, furniture, font images, GUI backgrounds) visible to Bedrock players. It converts locally on the server; there is no hosted converter. Licensed AGPL-3.0-only.

## Build and test

```
./gradlew build                      # all modules + tests; produces the two jars below
./gradlew :common:test               # converter tests
./gradlew :cli:test                  # offline Nexo converter tests
./gradlew :common:test --tests "dev.bedrockbundler.common.convert.FontConverterTest"
./gradlew :common:test --tests "*ConverterTest.legacyCustomModelDataItem"
```

Convert a real Nexo pack without a server (handy to inspect output or check a converter change):

```
java -jar cli/build/libs/BedrockBundler-cli-<version>.jar --nexo test-nexo/plugins/Nexo --pack test-nexo/plugins/Nexo/pack/pack.zip --out <dir>
```

- `paper` compiles against the public Nexo API (`repo.nexomc.com`). The ItemsAdder hook (`paper/src/itemsadder/java`) is only compiled when `libs/ItemsAdder-*.jar` exists; that jar is a paid plugin: git-ignored, never commit or redistribute it.
- `cli` is the offline converter for a Nexo folder (`NexoFolder`, `NexoBlockStates`), with its own tests.
- No Java toolchain is configured: any JDK 21+ builds, `options.release = 21`.
- Outputs:
  - `paper/build/libs/BedrockBundler-<version>.jar`, the server plugin. Its `processResources` embeds the extension shadow jar as `BedrockBundlerExtension.jar`.
  - `geyser-extension/build/libs/BedrockBundlerExtension-<version>.jar`, with Gson relocated.
- `ConverterTest` also writes the unpacked output to `common/build/test-output/` for manual inspection.

## Architecture

Four Gradle modules (`cli` is the offline converter above). Data flows plugin → bundle → extension:

1. **`paper`**
   - A `ContentSource` (`nexo.NexoSource`, `itemsadder.ItemsAdderSource`, the latter loaded by name) snapshots the registry and reads the generated pack. Threads go through `Scheduling` (Paper's region schedulers), so the plugin is Folia/Canvas-safe.
   - `ItemsAdderListener` receives the generated pack from `ItemsAdderPackCompressedEvent`. When ItemsAdder doesn't rebuild an unchanged pack, it falls back to reading `ItemsAdder/output/generated.zip` 5 s after `FIRST_LOAD`.
   - `IaRegistryReader` snapshots the ItemsAdder registry into `IaItem`/`IaBlock` records on the server thread.
   - `ConversionService` converts on a single background thread and writes a **bundle** to `plugins/BedrockBundler/bundle/`. A `Deployer` then delivers it: `LocalGeyserDeployer` (Geyser-Spigot on the same server; also installs the embedded extension jar), `FolderDeployer` or `HttpDeployer`.
2. **`common`** is the converter and has no Bukkit or Geyser dependency.
   - `Converter.convert(ConversionInput, Options, packName)` → `Bundle` (item/block specs) + `.mcpack` + Geyser v2 mappings JSON (`MappingWriter`, export only).
   - `JavaPack` → `ModelResolver` → `ItemConverter` / `BlockConverter` / `ArmorConverter` / `FontConverter` → `BedrockPack`.
3. **`geyser-extension`**
   - On `GeyserPreInitializeEvent` it syncs per `config.properties` (`LOCAL`/`FOLDER`/`HTTP`), copies `bundle/` to `active/` and loads it.
   - It then registers items via the API (`GeyserDefineCustomItemsEvent`), blocks with `registerOverride(javaState, ...)`, and the pack from `active/`.

Nexo keeps much of its furniture state out of the entity and sends it with packets only: the displayed item, door open/closed transformation (`FurnitureDoor.isOpen`) and connectable variants (`nexo:connectable` PDC state). `ContentSource.displayItem`/`transformation` rebuild what Java players see; `Converter.modelVariants` registers `<item_model>_<variant>` definitions of furniture items as items of their own. Nexo 1.30's `ConnectableMechanic.getStraight()` etc. return the default key, so `NexoSource` derives the variant name itself. Geyser turns one Bedrock tap into two interactions, so `NexoListener` debounces furniture interactions of Bedrock players.

Furniture is runtime, not conversion: `FurnitureMirror` (paper) spawns a non-persistent, invisible marker armor stand per furniture `item_display` (Nexo or ItemsAdder), from a task on the display's own entity scheduler. The stand is `setVisibleByDefault(false)` and shown only to Bedrock players (`BedrockPlayers`: Floodgate, else Geyser API), and wears the display's item. The item's attachable has a `furniture` pose used when `q.owner_identifier == 'minecraft:armor_stand'`. Geyser's API cannot tell an extension which item a display entity shows, hence this design.

## Invariants that are easy to break

- **Bump `Converter.CONVERTER_VERSION` whenever converter output changes.** It is part of the bundle hash and stored in the bundle. Unchanged hashes skip conversion, and the extension ignores bundles from a different converter version.
- **Geyser loads custom content only at startup.** Every content change needs conversion then a restart. Never overwrite a pack Geyser is serving: it hashes the file once, which is why the extension serves from `active/`, not `bundle/`.
- **ItemsAdder's generated pack is protected.** `JavaPack` and `Textures` undo this; keep that in mind before assuming plain paths:
  - real item definitions live in `ia_overlay_*` dirs (overlays for the newest pack format are applied);
  - models reference atlas sprite aliases like `ia:18`, resolved via `atlases/*.json` `single` sources;
  - PNGs have broken zlib checksums, repaired in `Textures.repairPng`;
  - zip local headers are scrambled, so read zips from disk with `ZipFile` (`JavaPack.readZip(Path)`), not `ZipInputStream`.
- **The ItemsAdder API jar is obfuscated without generics or inner-class metadata.** Cast elements of `getNamespacedIdsInRegistry()`. Use `var` for `PackEntry`, and `String.valueOf(event.getCause())` instead of naming `ItemsAdderLoadDataEvent.Cause`.
- **Always use `Locale.ROOT` for case conversion.** A Turkish JVM locale turns `i` into `İ` (ItemsAdder itself breaks this way; servers need `-Duser.language=en`).
- **Bedrock identifiers must start with a letter.** `ResourceId.flat()` strips ItemsAdder's leading `_` (namespace `_iainternal`); the client refused to join otherwise.
- **Legacy names are intentional.** The project was called BedrockAdder; `LEGACY_*` constants and `migrateLegacyFolder` still name `BedrockAdder`/`bedrockadder` to migrate old installs (config, extension jar and config, stand tags). Don't rename them away.
- **3D attachables** need the top bone `binding` (`ITEM_BINDING` in `GeometryConverter`) or models float at the player origin. Below it sit arm and hand bones that replay player motion Bedrock only plays for vanilla items (shield blocking), then the display-transform bones. Java→Bedrock geometry mirrors x (`origin = [-to.x + 8, from.y, from.z - 8]`).

## Fonts and GUI backgrounds

- **Glyph sheets.** `FontConverter` maps bitmap glyphs to Bedrock `font/glyph_XX.png` sheets. Bedrock has one font, so it reads `minecraft:default` first, follows `reference` providers, then every other namespace's fonts (Nexo uses `nexo:default` and `nexo:shift`). Besides private use it takes over rarely typed blocks Nexo hands out (U+A4xx), never scripts players write (`replaceable()`). Bedrock draws each 16×16-grid cell at a fixed size, so one cell represents `fontCellUnits` Java font pixels (default 32) and the Java `height`/`ascent` place the glyph inside it.
- **Offsets.** Positive offsets become invisible glyphs whose width is set by an alpha-1 pixel; negative offsets cannot move text on Bedrock and become invisible zero-width glyphs, so a written page never shows Bedrock's own button icons (U+E0xx/E1xx) in titles.
- **GUI backgrounds.** Glyphs with height ≥ 64 are GUI backgrounds: invisible in the sheet. `ChestUiWriter` writes `ui/chest_screen.json` that adds them to the classic chest `root_panel` with `"visible": "(not ($atext - '<glyph>' = $atext))"`, where `$atext` is `$container_title`. It also redraws the title above them. The `requires` + `ignored` variant did not work in game.
- **Background position.** ItemsAdder uses one offset (`gui-offset-x`, -16). Nexo puts a shift before each glyph in menu titles: `GlyphTitleOffsets` reads `<shift:N><glyph:id>` from config YAML into `Options.guiOffsets`; `ChestUiWriter.offsets` covers titles built in code (same-looking glyph, or a texture exactly as wide as the chest window).
- **Coordinate constants.** The constants in `ChestUiWriter` (Java title at (8,6), Bedrock slots 4 px lower) were verified pixel-exact against screenshots.

## Testing in game

- **Unit tests** cover the converter with fixture packs that mirror real ItemsAdder output (`ItemsAdderPackLayoutTest`). Several bugs only showed up against a real ItemsAdder pack and real Bedrock client, so verify visual changes in game.
- **Local test server.** `test/` is a git-ignored Leaf test server, started with `start.bat`; `test_ss/` holds Java vs Bedrock comparison screenshots. Don't replace the plugin jar while that server is running: ask the user to stop it, then copy `paper/build/libs/BedrockBundler-*.jar` into `test/plugins/`. Two restarts are needed for converter changes: convert, then Geyser load.
- **Nexo test server.** `test-nexo/` (git-ignored) is the same Leaf server with Nexo instead of ItemsAdder, on port 25566. The same jar-swap rule applies.
- **Reference files.** Vanilla Bedrock JSON can be read for reference from the installed client's `data/resource_packs/vanilla/__brarchive/*.brarchive` (uncompressed archives: `ui`, `animations`, `animation_controllers`, `models/entity`). Attachables live in versioned packs (`vanilla_1.20.0`, `vanilla_1.26.10`, ...). The shield and elytra poses were derived from them. Versioned packs override the base `vanilla` one: always read the newest version that defines a file. Do not copy them into the pack, with one exception: `common/src/main/resources/bedrock/entity/` holds the vanilla horse, llama and trader llama client entities (from `vanilla_1.26.10`). The vanilla files lack `enable_attachables`, so without these copies custom animal armor is never drawn. A pack can only replace a client entity whole. `bedrock/models/` holds their geometry (from `vanilla_1.21.90`). The armor is drawn on that geometry, inflated by `ArmorConverter.ARMOR_INFLATE`; at the animal's own size it flickers, and attachable bones ignore animated scale. Refresh these copies when Mojang changes them.
- **Bedrock path limit.** The client skips pack files whose path has 80 or more characters. `BedrockPack.shortPath` renames long definition files to a hash.
- **Bedrock screenshots** the user takes in game are saved under `%APPDATA%\Minecraft Bedrock\Users\<id>\games\com.mojang\Screenshots\`; read them there instead of asking for uploads.
