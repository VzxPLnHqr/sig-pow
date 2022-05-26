# sig-pow
proof of work via ecdsa signature length

## What?
Robin Linus came up with an interesting encoding of proof of work (PoW) which can be verified directly within bitcoin script.

Here is an attempt at a naive implementation of the idea.

## Status
Pre-proof-of-concept (aka probably broken)

## Building / Usage
Currently this is just a single script written in scala as an [ammonite script](https://ammonite.io). To run it, you will need ammonite installed. This is easy to do if you use the Nix package manager.
1. Install [Nix](https://nixos.org) package manager if not already installed.
2. `nix-shell -p ammonite` will get you a shell with the `amm` command available so you can run the script.
3. `amm -i sig-pow-example.worksheet.sc` will run the script (the `-i` may or may not be necessary depending on whether the script expects to accept user input from the console).