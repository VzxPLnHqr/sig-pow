# sig-pow
proof of work via ecdsa signature length

## What?
An interesting way to encode and verify proof of work directly within bitcoin script (with no new opcodes needed) is via signature length. The idea is from Robin Linus [here](https://gist.github.com/RobinLinus/95de641ed1e3d9fde83bdcf5ac289ce9).

Here is an attempt at a naive implementation of the idea.

### Example Use-case - mining competition for choice of genesis block
1. Alice funds a UTXO with 1.0 BTC but which are "work-locked."
2. The parameters of the work-lock are chosen such that a potential spender
   (Bob) who contributes more work will be able to create a valid transaction
   in every way except for the locktime.
3. If Charlie contributes less work than Bob, he too can still create a valid
   spending transaction for the UTXO, but would have a longer locktime than Bob,
   and therefore would likely not be the ultimate spender.
4. Bob, Charlie, Drake, and even Alice herself, could form a mining-pool of some
   sort in a shared effort to create a valid spending transaction with a locktime
   earlier than all others.
5. In essence, it is a mining competition for the "genesis block" of what could 
   become a side-chain or similar.
6. This example needs more "work" in order to even make much sense yet, but we
   have to start somewhere!


## Status
Pre-proof-of-concept (aka probably broken)

## Building / Usage
Currently this is just a single script written in scala as an [ammonite script](https://ammonite.io). To run it, you will need ammonite installed. This is easy to do if you use the Nix package manager.
1. Install [Nix](https://nixos.org) package manager if not already installed.
2. `nix-shell -p ammonite` will get you a shell with the `amm` command available so you can run the script.
3. `amm -i sig-pow-example.worksheet.sc` will run the script (the `-i` may or may not be necessary depending on whether the script expects to accept user input from the console).