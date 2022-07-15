# sig-pow - work-locked outputs
proof of work via ecdsa signature length

## What?
An interesting way to encode and verify proof of work directly within bitcoin script 
(with no new opcodes needed) is via signature length. The idea is originally from 
Robin Linus [here](https://gist.github.com/RobinLinus/95de641ed1e3d9fde83bdcf5ac289ce9).

Basically, ECDSA signatures in bitcoin, are typically 70-73 bytes in length. However,
it is possible to generate shorter signatures -- it just might take a lot of work!

[This blog post](https://blog.eternitywall.com/content/20171212_Exact_Probabilities/) gives
the probability distribution for bitcoin (elliptic curve secp256k1) ecdsa signatures
of a particular length, in bytes. Finding signatures smaller than 70 bytes becomes
exponentially more difficult. We can exploit this fact as a means to encode proof of work
in a manner which is verifiable by the bitcoin network. 

**This concept, while tedious and convoluted, can be used on mainnet today**. 
No protocol upgrades needed! Unfortunately it is not usable with tapscript/schnorr,
as all valid schnorr signatures in bitcoin are the same length. Nevertheless, what 
we try to put together here is a demonstration of the concept. 

If it has any utility, then perhaps some dedicated op_codes can be created for 
creating/verifying work-locks in a more streamlined way. Afterall, proof-of-work,
is an effective filtering mechanism. Work-locks, even in this inefficient signature-length
encoding, are easy for bitcoin nodes to verify relative to the work that went into unlocking them.

### A work-a-lot-tery!
Working to unlock a work-locked output is somewhat like traditional bitcoin mining,
but possibly better! With a properly calibrated work-lock, even a little bit of 
mining will produce a valid spending transaction (a ticket in this lottery).

The ticket is a valid transaction in every way except that the nLocktime might 
prevent it from being broadcast and included in a block for, say, another 200 years!

More work will produce tickets with a sooner nLocktime.

In essence, this is **provably fair proof of future proof of work (PoFpOW?)**.

## How?
1. Alice creates `N` private keys (`priv_key_i` for `i` in `0..N`), and makes them 
   available to Bob (or the whole world). 
2. These keys can be derived from a seed string of her choosing. If the seed 
   string includes information about a point in time (such as a recent bitcoin 
   block header), then we can have confidence that even Alice, the creator and/or
   funder of the worklock, will have no real advantage over any other participants.
2. For each key, Alice assigns a maximum acceptable signature length (in bytes) `max_sig_length_i`.
3. Alice can use the signature lengths to construct an `N` bit number, in base 2,
   as follows:
4. If the length of the signature for key `i` is less than or equal to the `max_sig_length_i`, then
   set bit `i` equal to zero. Otherwise set bit `i` equal to 1.
5. By choosing `N` appropriately, a set of signatures can express a number between
   `0..2^(N-1)`.
6. By choosing `max_sig_length_i` appropriately (with the help of the probability distribution for
   ecdsa signature lengths, and some other number crunching), some of these numbers
   will be "more difficult" to actually express than others.
7. Building up such a number inside a bitcoin script and using it as, say, the
   input for an `OP_CHECKLOCKTIMEVERIFY` calculation, is interesting.
8. Calibrated appropriately, more work will unlock the output sooner. 

#### Example (future) Use-case - trustless sidechain
We might be able to use this concept to create a sidechain which is trustless. 
The mining competition to unlock work-locked utxo(s) is structured such that the 
incentive for miners is to simultaneously peg-out all the side-chain participants.
Then, the sidechain essentially evaporates.
1. this example needs more explanation!

#### Example (future) Use-case - mining competition for choice of genesis block
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
6. In some ways, for the period of time where the original utxo is still unspendable
   (because enough work has not yet been contributed to move the timelock into the
   present) it is actually already a side-chain, yet with the curious property of 
   unwinding once a spending transaction becomes valid.
6. This example needs more "work" in order to even make much sense yet, but we
   have to start somewhere!


## Status
Pre-proof-of-concept (aka probably broken). Just some worksheets so far doing 
some preliminary number crunching and transaction constructing.

## Building / Usage (application)
The application currently being built in this repository is very simple:
1. use the [Nix](https://nixos.org) package manager to install dependencies by first installing Nix and then running `nix-shell -p scala` which will ensure that you have a decent version of scala/java installed
2. for the build, we use the `mill` build tool [Mill Website](https://com-lihaoyi.github.io/mill), which also requires java
3. a bootstrap script for mill has been checked into the repository already
4. `./mill -i main.run` runs the `main` module of the `bulid.sc` project (the `-i` allows for `readLine` and `ctrl+C` to work properly)

## Building / Usage (worksheets)
There are some exploratory worksheets written in scala as an [
ammonite script](https://ammonite.io). To run it, you will need ammonite installed. 
This is easy to do if you use the Nix package manager.
1. Install [Nix](https://nixos.org) package manager if not already installed.
2. `nix-shell -p ammonite` will get you a shell with the `amm` command available so you can run the script.
3. `amm -p sig-pow-example.worksheet.sc` will run the script and drop you into a REPL session (remove the `-p` if you just want to run it and exit)

## References/Acknowledgements
1. ...