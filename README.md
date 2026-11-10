# WebDisplays Fork - Aeronautics/Sable/Create Compat

An unofficial fork of WebDisplays for NeoForge 1.21.1, with fixes for Create Aeronautics, Offroad, Simulated, and any other mod built on Sable sub-levels. This mod allows you to create screens in your Minecraft world and browse the internet — including inside moving vehicles.

## Features

- Create in-game screens that display web content
- Browse the internet from within Minecraft
- Watch videos, view websites, and more on your custom displays
- Multiplayer support - share screens with other players on servers
- **Sable sub-level compatibility**: screens render and are clickable inside Create Aeronautics (and other Sable-based) vehicles, with correct pixel-accurate interaction at Sable's extreme plot-grid coordinates
- Optional Create-contraption rendering fix (works fine without Create installed)

## Requirements

- **Minecraft**: 1.21.1
- **Mod Loader**: NeoForge 21.1.x
- **Java**: 21
- **Rinku**: 3.0.0+ (Required dependency - provides the Chromium Embedded Framework)

## Installation

1. Install NeoForge for Minecraft 1.21.1
2. Download and install [Rinku](https://www.curseforge.com/minecraft/mc-mods/rinku) (required dependency — this fork no longer uses the old, discontinued MCEF)
3. Download WebDisplays and place it in your `mods` folder
4. Launch Minecraft

## Credits & Attribution

This is an **unofficial community fork** of WebDisplays for NeoForge 1.21.1.

### Original Authors
- **montoyo** - Original creator of WebDisplays
- **CinemaMod Group** - Current maintainers of the official WebDisplays mod

### Contributors to the Original Mod
- GiantLuigi4
- ds58
- Mysticpasta1
- WaterPicker

### 1.21.1 Port
- **BrotherBill** - Ported to NeoForge 1.21.1

### This Fork
- **Agushh** - Sable sub-level compatibility (Create Aeronautics, Offroad, Simulated, etc.), Rinku migration, Create-contraption rendering fix

### Dependency
- **[Rinku](https://github.com/Keksuccino/Rinku)**, by Keksuccino — the actively-maintained successor to MCEF, provides the Chromium browser engine this mod renders with. Not bundled; install separately.

## Original Project

This port is based on the official WebDisplays repository maintained by CinemaMod Group:
- **GitHub**: https://github.com/CinemaMod/webdisplays
- **CurseForge**: https://www.curseforge.com/minecraft/mc-mods/webdisplays
- **Modrinth**: https://modrinth.com/mod/webdisplays
- **Discord**: https://discord.gg/rNrh5kW8Ty

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

The original WebDisplays mod is also MIT licensed, allowing for modification and redistribution.

## Building from Source

```bash
# Clone the repository
git clone https://github.com/Agushh/webdisplays-aero-compat.git
cd webdisplays-aero-compat

# Build the mod
./gradlew build

# The built jar will be in build/libs/
```

## Disclaimer

This is an unofficial port and is not affiliated with or endorsed by montoyo or CinemaMod Group. For the official version of WebDisplays, please visit the links above.

If the official maintainers release a 1.21.1 version, we recommend using their version instead.
