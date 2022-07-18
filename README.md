# sig-pow - work-locked outputs
proof of work via ecdsa signature length

## What?
An interesting way to encode and verify proof of work directly within bitcoin script 
(with no new opcodes needed) is via signature length. The idea is originally from 
Robin Linus [here](https://gist.github.com/RobinLinus/95de641ed1e3d9fde83bdcf5ac289ce9).

Basically, ECDSA signatures in bitcoin, are typically 70-73 bytes in length. However,
it is possible to generate shorter signatures -- it just might take a lot of work!

A UTXO locked with a script of the following form needs a signature of length 61 bytes or less
to be spent.
> `<pubkey1> <sig1> OP_SIZE 61 OP_LESSTHANOREQUAL OP_VERIFY OP_SWAP OP_CHECKSIGVERIFY`

Such a UTXO will remain unspendable until an amount of hashpower a few orders of magnitude larger
than the current bitcoin network is directed towards solving it (or until the ecdsa
signature algorithm itself is broken). An outline of why this is so is covered later
in this document.

Finding ecdsa signatures smaller than 70 bytes becomes exponentially 
more difficult.[^prob_dist_ref] We can exploit this fact as a means to encode proof 
of work in a manner which is verifiable by the bitcoin network.[^schnorr_note] 

**This concept, while perhaps tedious and convoluted to express in current bitcoin script, can be used on mainnet today**. 
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

### Calibrating a Work-Lock
Work-locks as described here are somewhat convoluted in practice, considering the
reasonably simple thing they are trying to achieve. This is mostly due to some limits
in the expressiveness of bitcoin script. Tis is not intended to be construed
as a complaint about bitcoin per se, as there are many good reasons why bitcoin 
script is less expressive than other languages. Rather, working within bitcoin 
script is simply a design constraint which we try to workaround here when developing
and calibrating a work-lock. 

#### Quantifying the Work

##### A quick Proof of Work Refresher

The most common use of PoW in bitcoin is visible in the (double) sha256 hash
of a block header. There are some parameters included in the blockheader which
verifying nodes unpack and use to verify a block. However, in essence, what they
are actually doing is simply taking `y = sha256(sha256(block_header))`, interpreting
`y` (a 32-byte = 256-bit vector) as a 256-bit number. Of course, there are many
ways 256 bits can be interpreted as a number. Bitcoin takes a simple appraoch and
treats the bits of `y` in sequence `bit_256,bit_255,bit_254,bit253,...bit3,bit2,bit1,bit0`.
Each bit in the sequence is raised to its respective power of two, and the results
are summed together. If `y` is less then another 256-bit number `t`, commonly called
the target, which is encoded in the blockheader, then the block has met the proof
of work requirement. Nodes can then move on and start verifying the other information
which the blockheader committed to, such as transactions.

Now, let us consider the above process in reverse. If we know `t`, and we are confident
that the hash function `h(x) = sha256(sh256(x))`, is not "broken" and its outputs are
(generally accpeted to be) uniformly distributed, then we can calculate the probability
`p_t` that a chosen input `x` (the block header) will meet the proof of work requirement.
This probability is `p_t = t / (2^k - 1)` where, for `h = sha256`, `k = 256`.

With `p_t` in hand, we can then calculate the expected number of trials it should take
somebody to find such an `x`. Because the trials are considered to be independent,
this is modeled by the [Geometric Distribution](https://en.wikipedia.org/wiki/Geometric_distribution).
The expected expected number of trials needed is `num_trials = 1 / p_t`. For small `p_t`,
`num_trials` can be a very large number, so sometimes `log_2(num_trials)` is used
instead. 

Here, instead of `log2(num_trials)` we like to instead calculate the Shannon entropy 
of the geometric distribution with parameter `p_t` and use this number as our 
definition of "work." Conveniently, the Shannon entropy gives a result in bits, and
we can think about it, informally, as related to the logarithm of the "effective
number of trials," which is not quite the same as "expected number of trials," but
we will not dwell on that here. 

More importantly, by using the entropy as our proxy for calculating "work," we can
define a function `w(p) = (-(1-p)*log2(1-p) - p*log2(p)) / p` (which is just the
Shannon entropy of the geometric distribution with parameter `p`) to calculate the
the "effective amount of work" (in bits) it takes, to flip a bias coin with 
probability `P(HEADS) = p` and `P(TAILS) = 1 - p`, until a heads is achieved.

The double sha256 hash of a recent bitcoin block header has 76 zeroes as its most 
significant bits. Running the calculation here for that same block header gives,
conveniently, a `p` which corresponds to a `w(p) = 76 bits`. Math is neat!

##### What about non-uniform distributions, like lengths of ecdsa signatures?
One advantage of viewing and calculating "work" in the way outlined above is that
we can lift the concept into other distributions and yet still make (somewhat) reasonable
comparisons across them. For example, while we know the work required to mine a
recent bitcoin block[^recent_block] is 76 bits of work. We can then ask, how short does an ecdsa
signature need to be for us to have confidence that it took *at least 76 bits of work*
to find such a signature? The details, with some other irrelevent calculations are 
outlined in [this worksheet](./ecdsa-sig-length-probability.worksheet.sc), but the
answer is:

> Finding a 61 byte (or smaller) ecdsa signature is equivalent to performing approximately
  79.9 bits of work.
> Finding a 62 byte signature (or smaller) is equivalent to 72.12 bits of work.

The difference between these two signature lengths is approximately 8 bits, which is
not terribly suprising, since there are 8 bits in a byte, and we know that the liklihood
of signatures by length trails off exponentially as signatures get smaller. Nevertheless,
this means that finding a 61 byte signature is much much much much harder than finding
a 62 byte signature. This is what makes "calibrating" work locks hard and why, in order
to be able to calibrate them at all, we end up introducing multiple signatures along with
a mechanism to aggregate the results of the expected work, translated (in the case of
a work-a-lot-tery), into a locktime constraint.

In brief, "work," when calculated in the manner outlined here is not additive in
the usual sense. Rather, work "accumulates" only in the cases where we can show
that the output of one work calculation is used as the input to another work calculation.
Sound familiar? This essentially defines a proof-of-work blockchain. For the blockchain,
we can calculate the work for each block, and add them together to get a total amount
of accumulated work. We will write this operation `w1 |*| w2`, for two "works" `w1` 
and `w2` where the output of `w1` is included as input of `w2`. The right hand side 
is the usual addition of real numbers.

However, in situations where we cannot know that the work is performed sequentially,
then we have to assume the work can be done in parallel. In such a circumstance, two
"works" `w1` and `w2` are "combined" as `w1 |+| w2 = log2(2^w1 + 2^w2)`. The right
hand side is the usual notion of logarithm, exponention, addition, etc.

The notation above is inspired by the mathematical notion of a semiring. In essence,
"work" in the thermodynamic limit as contemplated here is combined according to the
rules of the (base 2) [Log Semiring](https://en.wikipedia.org/wiki/Log_semiring). Whether
this is a useful or even appropriate analogy remains to be seen, but is convenient
for our purposes at hand so far. There is also some prior efforts in the literature to
link these two notions[^semiring_ref].

This paragraph serves as a reminder to the author and an aknowledgement to anybody 
who reads this far that the above will/should be significantly cleaned up and clarified!
But we digress, and need to get back to coding...



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
1. Robin Linus for showing that it is possible to express and verify proof of work within bitcoin script today.
2. Ruben Somsen for bitcoin wizardry and maintaining a helpful Telegram group of other fellow wizards.
3. Brill Saton for mental gymnastics and code golfing with bitcoin script.

### Footnotes
[^semiring_ref]: [Marcolli M. & Throngren R., Thermodynamic Semirings](https://arxiv.org/abs/1108.2874) - Not exactly the same as we
   contemplate here, but interesting nonetheless.

[^recent_block]: Bitcoin block with hash (in hex) 000000000000000000080e3308aab615e86e4241e7d4ed4364500edd38aa90ac, when expressed in binary has 76 leading zeros.

[^prob_dist_ref]: [This blog post](https://blog.eternitywall.com/content/20171212_Exact_Probabilities/) gives
a calculation of the probability distribution for bitcoin (elliptic curve secp256k1) ecdsa signatures
of a particular length, in bytes. [This worksheet](./ecdsa-sig-length-probability.worksheet.sc) contains a copy of the python code and some calculations. Note: we have not confirmed whether the distribution presented is correct, but simply assume it is for now. This assumption
should be verified by any interested.

[^schnorr_note]: This applies only to pre-taproot/pre-schnorr bitcoin, such as segwit_v0. Schnorr signatures in bitcoin are guaranteed
                 to all be the same length.