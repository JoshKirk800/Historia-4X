# Historia 4X

Guide a civilization from its first settlement to global victory.

Historia 4X is a desktop-first, open-source turn-based strategy game. Explore the world, found cities, develop science and culture, shape a religion and ideology, negotiate with rivals, and fight wars against AI opponents designed to play the complete game.

## What to expect

- Complete campaigns spanning exploration, expansion, diplomacy, development, and war.
- Scientific, cultural, diplomatic, and domination paths to victory.
- Trade, religion, ideology, archaeology, and international politics.
- One coherent ruleset instead of separate historical compatibility modes.
- Deterministic simulation and testing focused on stronger, more reliable AI.
- A fast 2D presentation built for desktop play and long games.

## Current state and direction

Historia already has a playable rules baseline. Automated games run from their opening turns through victory, supported by fixed-seed regression studies, deterministic replays, and full-game traces.

Current work is bringing more advanced strategic, diplomatic, tactical, and operational AI into the single Historia ruleset. The next stages complete the remaining game systems, product identity, licensing and provenance review, desktop packaging, and visual polish.

There is no supported public release yet. The goal is a strong standalone game rather than exact compatibility with another game's behavior, saves, or mods. Deliberate departures are kept when they are documented and produce better complete-game outcomes.

Development uses AI coding assistance under human direction. Changes are reviewed against their sources, tested, and exercised in deterministic simulations before they are retained.

## Origins and attribution

### Unciv

Historia 4X is a hard fork of [Unciv](https://github.com/yairm210/Unciv), created by yairm210 and the Unciv contributors.

Historia retains Unciv's Kotlin/libGDX engine, user interface, save system, ruleset architecture, tooling, media, and other substantial inherited work. The repository preserves Unciv's Git history so inherited work remains traceable to its original authors.

Historia diverges from upstream in product scope, rules, AI, testing, and release priorities. It is not an official Unciv release, and Historia-specific support requests should not be directed to the Unciv project.

### Civilization V and Vox Populi

Historia uses Brave New World, the Community Patch, and Vox Populi as design and behavioral references. Relevant rules and AI routines begin with a cited source function, Lua routine, or data value, then are implemented for the Kotlin/Unciv architecture. Project policy is not to copy Firaxis or Vox Populi source code.

Source behavior is a starting point rather than an unconditional acceptance criterion. Historia does not distribute the Civilization V executable or Firaxis assets, load Civilization V saves or mods, or reproduce the original game's 3D presentation.

Historia is not affiliated with or endorsed by the Unciv, Vox Populi, Firaxis, or Take-Two teams. A public release containing the planned Vox Populi-informed work remains subject to completion of the project's licensing review.

## Try the current build

There is no prebuilt release yet, but the current desktop version can be run from source. Builds may still contain inherited Unciv names while the rebrand is completed.

Requirements:

- Git
- JDK 11 or newer

On Windows:

```bat
gradlew desktop:run
gradlew desktop:dist
gradlew tests:test
```

On Linux or macOS:

```bash
./gradlew desktop:run
./gradlew desktop:dist
./gradlew tests:test
```

These commands respectively run the game, build the desktop JAR, and run the automated tests.

## License and credits

Historia's source code is available under the [Mozilla Public License 2.0](LICENSE), except where otherwise noted.

`TextureArraySpriteBatch.java`, inherited from Unciv and originally forked from `carlislefox/libgdx-texture-array-batch`, is available under CC0 1.0 Universal; see [LICENSE-TextureArraySpriteBatch](LICENSE-TextureArraySpriteBatch).

Media files use a mixture of CC BY-SA 4.0, CC BY 3.0/4.0, CC0, and public-domain terms. See [docs/Credits.md](docs/Credits.md) for their attribution.
