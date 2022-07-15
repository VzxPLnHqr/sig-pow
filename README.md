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
The exact amount of time depends on the parameters of the work-lock, but it is 
possible to create work-locks which are short (easy) or nearly impossible (long,
taking centuries, if ever, to unlock).

More work will produce tickets with a sooner nLocktime.

In essence, this is **provably fair proof of future proof of work (PoFpOW?)**.

#### Live (Signet) Example
The first output (index 0) of [this transaction on signet](https://mempool.space/signet/tx/f946a09f1f33b92506c39993532070247eae36921c70543ff386c578a78397b1) funds a naive work-lock which 
requires 4 signatures to each be less than or equal 73 bytes. All such bitcoin
signatures meet this criteria, so it was a trivial work-lock to "solve," but you
can get an idea of how it works by inspecting the [spending transaction](https://mempool.space/signet/tx/695b89db80ff904b1cd89243a6588b5dc882d921f7faf6496ed9bc87bc318990).

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

## Status
Pre-proof-of-concept (aka probably broken). Just some worksheets so far doing 
some preliminary number crunching and transaction constructing.

### Example Additional Use-cases for Work-locks
#### (future) Use-case - spam prevention
Work-a-lot-tery tickets are just another representation of PoW, but which has been
encoded in a bitcoin transaction.
1. Alice, Bob, and Charlie, each operate their own websites/servcies. 
2. To prevent spam/DoS attacks, they require patrons to occassionally submit
   work-a-lot-tery tickets with locktimes below a threshold.
3. Merchants like Alice, Bob, and Charlie can individually, or collectively, adjust
   up/down their acceptable locktime threshholds. They can do this with a formal
   protocol, or simply via word of mouth.
4. The above assumes that the tickets are created (mined) by customers and kept 
   private, but that a customer might present the same ticket to multiple merchants.
   For further protection, merchants could require that a ticket includes payment
   to them in one of the outputs.
  
#### (future) Use-case - trustless sidechain
We might be able to use this concept to create a sidechain which is trustless. 
The mining competition to unlock work-locked utxo(s) is structured such that the 
incentive for miners is to simultaneously peg-out all the side-chain participants.
Then, the sidechain essentially evaporates.

##### mining competition for side-chain peg-out
1. Alice funds a UTXO with 1.0 BTC but which are "work-locked."
2. Bob, Charlie, Drake, and even Alice herself, could form a mining-pool of some
   sort in a shared effort to unlock the funds.
3. In doing so, they are essentially forming a side-chain which can have its own 
   consensus protocol, paying each other (in the sidechain) for contributed work, etc.
5. At some point, their work will pay off and the work-lock will be solved.
   Depending on the parameters of the work-lock, this might be in the far future! Or
   Regardless, the rules of the sidechain were such that "if this sidechain dissolves 
   immediately, would we all feel fairly rewarded?" is a true statement.
6. In essence, it is a mining competition for the "final block" (the opposite
   of a "genesis block") of a side-chain.
7. The consensus rules of the side-chain can be somewhat arbitrary, so long as the
   latest "state" of the sidechain includes the current "best yet" peg-out transaction.
   The parameters of the work-lock and the game theory / consensus design of the side
   chain work in tandem to (in expectation) produce this outcome.

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
1. Robin Linus for showing that it is still (barely) possible to express and verify proof of work within bitcoin script today.
2. Ruben Somsen for bitcoin wizardry and maintaining a helpful Telegram group of other fellow wizards.
3. B___ S___ for mental gymnastics and code golfing with bitcoin script.