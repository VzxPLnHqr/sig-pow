# sig-pow
proof of work via ecdsa signature length

## What?
An interesting way to encode and verify proof of work directly within bitcoin script (with no new opcodes needed) is via signature length. The idea is from Robin Linus [here](https://gist.github.com/RobinLinus/95de641ed1e3d9fde83bdcf5ac289ce9).

Here is an attempt at a naive implementation of the idea.

## Status
Pre-proof-of-concept (aka probably broken)

## Building / Usage
Currently this is just a single script written in scala as an [ammonite script](https://ammonite.io). To run it, you will need ammonite installed. This is easy to do if you use the Nix package manager.
1. Install [Nix](https://nixos.org) package manager if not already installed.
2. `nix-shell -p ammonite` will get you a shell with the `amm` command available so you can run the script.
3. `amm -i sig-pow-example.worksheet.sc` will run the script (the `-i` may or may not be necessary depending on whether the script expects to accept user input from the console).