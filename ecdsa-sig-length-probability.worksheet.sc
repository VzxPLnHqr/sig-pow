// Scala 2.13.8

import $ivy.`org.typelevel::spire:0.18.0-M3`
import spire.math._
import spire.implicits._


// What is the probability mass function for length (in bytes) of an ecdsa 
// signature on the bitcoin (secp256k1) curve?

// According to https://blog.eternitywall.com/content/20171212_Exact_Probabilities/
// the probability mass function can be calculated exactly with the following
// python code. Rather than rewrite this in scala, we simply ran the python
// code and translated the results into scala.

/** 
    ```python
    # order of secp256k1 curve
    ec_order = 0xfffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141


    def drop_bytes_to_int(inp, n):
        """convert the int 'inp' to bytes (32), drop the first n bytes, return the corresponding int
        """
        return int.from_bytes((inp.to_bytes(32, "big")[n:]), "big")


    # Step 1: P(n-th byte strictly below 0x80)
    P1 = []
    for i in range(32):
        P1 += [min(1, 2 ** (8 * (32 - i) - 1) / (drop_bytes_to_int(ec_order, i) - 1))]
    # Step 2: P(n-th byte equal t0 0x00)
    P2 = []
    for i in range(32):
        P2 += [2 ** (8 * (32 - i - 1)) / (drop_bytes_to_int(ec_order, i) - 1)]
    # Step 3: P(int encoded in n or less bytes), i.e. cumulative distribution
    P3 = [1]
    for i in range(32):
        tot = 1
        for j in range(i):
            tot *= P2[j]
        P3 += [P1[i] * tot]
    P3 += [0]
    # Step 4: P(int encoded in n bytes)
    P4 = []
    for i in range(33):
        P4 += [P3[i] - P3[i + 1]]
    # Step 5: P(signature encoded in n bytes)
    P5 = []
    for i in range(66):
        tot = 0
        for j in range(i + 1):
            tot += (0 if j > 32 else P4[j]) * (0 if i - j < 0 or i - j > 32 else P4[i - j])
    P5 += [tot]
    ```
*/

// the above python code produces a probability mass function f(x)
// with non-zero values for x, which is the signature length,
// between 9 bytes and 73 bytes, inclusive. Here we represent that
// pmf as a Map[Int,Real]
val pmf_sig_length_bytes : Map[Int,Real] = Map(
    73 -> Real("0.25"),
    72 -> Real("0.498046875"),
    71 -> Real("0.249996185302734"),
    70 -> Real("0.00194549560546875"),
    69 -> Real("0.0000113845453597605"),
    68 -> Real("0.00000005925585355726"),
    67 -> Real("0.0000000002892219797"),
    66 -> Real("0.00000000000135537415"),
    65 -> Real("6.17568333711321E-015"),
    64 -> Real("2.75661578727992E-017"),
    63 -> Real("1.21127159023437E-019"),
    62 -> Real("5.25679741625985E-022"),
    61 -> Real("2.25861921217449E-024"),
    60 -> Real("9.62422630321279E-027"),
    59 -> Real("4.07254738627694E-029"),
    58 -> Real("1.71313725493047E-031"),
    57 -> Real("7.16968384288118E-034"),
    56 -> Real("2.98913803324356E-036"),
    55 -> Real("1.29566300271916E-038"),
    54 -> Real("5.92548353671717E-041"),
    53 -> Real("2.72357281122018E-043"),
    52 -> Real("1.24592993372745E-045"),
    51 -> Real("5.98354974302784E-048"),
    50 -> Real("3.27601927727104E-050"),
    49 -> Real("2.35919110611163E-052"),
    48 -> Real("1.77508417837088E-054"),
    47 -> Real("1.65393069642514E-056"),
    46 -> Real("1.1029912192089E-058"),
    45 -> Real("7.88446747030759E-061"),
    44 -> Real("6.52109181250135E-063"),
    43 -> Real("4.20012388367105E-065"),
    42 -> Real("2.93222799107629E-067"),
    41 -> Real("3.52707492436643E-069"),
    40 -> Real("5.02755778341421E-071"),
    39 -> Real("1.96387145760703E-073"),
    38 -> Real("7.67134148497124E-076"),
    37 -> Real("2.99662678648961E-078"),
    36 -> Real("1.17056862183519E-080"),
    35 -> Real("4.57264435829418E-083"),
    34 -> Real("1.78632145974433E-085"),
    33 -> Real("6.9795526162359E-088"),
    32 -> Real("2.72816175485324E-090"),
    31 -> Real("1.06752581675988E-092"),
    30 -> Real("4.18408366963788E-095"),
    29 -> Real("1.64440863848589E-097"),
    28 -> Real("6.51263704219092E-100"),
    27 -> Real("2.61478116044515E-102"),
    26 -> Real("1.07547723401171E-104"),
    25 -> Real("4.8134786169933E-107"),
    24 -> Real("2.66287128782412E-109"),
    23 -> Real("1.51598532438298E-111"),
    22 -> Real("7.83483865542454E-114"),
    21 -> Real("4.38884078738162E-116"),
    20 -> Real("2.98342255465351E-118"),
    19 -> Real("2.24414662465368E-120"),
    18 -> Real("1.86609944862506E-122"),
    17 -> Real("1.44532070335043E-124"),
    16 -> Real("1.13415557507525E-126"),
    15 -> Real("6.21969562503927E-129"),
    14 -> Real("4.68596422046382E-131"),
    13 -> Real("3.48636853539718E-133"),
    12 -> Real("1.82102335170786E-135"),
    11 -> Real("1.49905215080147E-137"),
    10 -> Real("1.7388630572546E-139"),
    9 -> Real("1.35320082276622E-141")
)

// Now, let us define the entropy for a given distribution, where the distribution
// is represented by a Map[Int,Real]
def entropy(pmf: Map[Int,Real]): Real = pmf.map {
    case (_,p) if(p == Real(0)) =>
        // when p == 0, -p*log(p) is defined by Shannon entropy to be 0 
        Real(0)
    case (_,p) =>
        // for any other p we do the usual -p*log(p) entropy calculation
        -p*log(p,2)

}.fold(Real(0))(_ + _) //summing the results

val entropy_siglength = entropy(pmf_sig_length_bytes) // 1.5187 bits
// The entropy of the length of an ecdsa secp256k1 signature is
// 1.5187 bits. This means that there exists an encoding of the byte length
// of the signature (a number between 9 and 73, inclusive) which, in expectation
// uses only 1.5187 bits to represent it. Naturally, since we do not have fractional
// bits, we must round up to two bits. A simple huffman coding would probably work
// fine. But we digress.

// Now, what is the probability of obtaining a signature of length less than or
// equal to x bytes? This is the cumulative probability distribution (cdf) and
// is easily calculated from our probility mass function.
def cdf(pmf: Map[Int,Real]):Map[Int,Real] = 
    pmf.keys.map(i => 
          i -> pmf.withFilter{ case (x, p) => x <= i}.map(_._2).fold(Real(0))(_ + _)
        ).toList.sortBy(_._1).toMap

// With the cdf available, we can now calculate the probability of a signature
// with length x less than or equal to 67 bytes. We convert to a Double for
// readability and obtain p = 0.00000000029058
val p_length_lte_67_bytes = cdf(pmf_sig_length_bytes)(67).toDouble

// Now that we know how to calculate p for a signature of a given length or less,
// we can determine the expected number of trials (signature creations) it would
// take to create such a signature. When p is close to 1, it takes very few trials
// but with p closer to 0, it takes many trials. 

// The geometric distribution allows us to determine the probability that,
// out of k trials, the kth trial (e.g. the last one) is successful if each
// trial is independent and had probability of success p. The expected value
// (number of trials) of the geometric distribution with parameter p, is 1/p.

// In our case, for a maximum acceptable signature length k (in bytes), we can 
// calculate the expected number of trials it took to find a such a signature.
// Actually, here we calculate the base 2 logarithm of the number of trials.
val expected_num_trials_log2_by_maxsiglength: Map[Int,Real] =
    cdf(pmf_sig_length_bytes).view.mapValues(p => ((Real(1)/p).log(2))).toMap

expected_num_trials_log2_by_maxsiglength.toList.sortBy(_._1).mkString
// For a signature length of maximum 73 bytes, it unsupprisingly, takes just 
// one trial. Yet for a signature length of maximum length 68 bytes, it takes
// an expected 2^24 number of trials! For a maximum length of 66 bytes it takes
// 2^39.4 number of trials. The number of trials necessary grows exponentially.

// We now have a way to determine the expected number of trials for a given 
// signature length. The trials are assumed to be independent, but that does
// not necessarily mean that each trial is performed with the same level of
// energy efficiency. Different implementations of the signature algorithm, while
// producing identical signatures, may take very different amounts of energy,
// space, and time to compute. In other words, each trial may require a different
// amount of work to perform that trial.

// Does there exist an objective measure of the average amount of work performed
// per trial? Yes! The entropy of the geometric distribution for a given signature
// length can be used as a lower-bound on the average amount of work required per trial.
// The units of our work-required (entropy) calculation is "bits" which is 
// convenient because we can directly visualize, and to some extent measure, the 
// impact of the work on the signature length. More work results in a greater
// likelihood of finding a shorter signature.

// Now, let us calculate the work required (in bits) to find a signature of
// of size less than or equal to a given length (in bytes).
val work_required_by_siglength: Map[Int,Real] = 
    cdf(pmf_sig_length_bytes).view.mapValues{ p =>
        // the formula for the entropy of the geometric distribution
        // with parameter p is given by (1/p)*( -(1-p)*log(1-p) - p*log(p) )
        require(p > Real(0)) // error if p == 0
        val q = Real(1) - p
        (Real(1)/p)*(-q*log(q,2) - p*log(p,2))
    }.toMap

work_required_by_siglength.toList.sortBy(_._1).toString

// Our next task is to turn this mapping of required work vs. signature length
// into a mapping of UTXO locktime vs. work required. Of course, a UTXO has
// associated with it some amount of satoshis (1 bitcoin == 100,000,000 satoshis).
// So we now need to join our information-theoretic deductions with some
// economics. 

// The longest possible utxo locktime in bitcoin right now is for the utxo to
// be unspendable until block 5,000,0000 or later. This is an extremely long
// time; on the order of centuries! However, since we are constructing a 
// work-locked utxo, it is perhaps somewhat natural that if minimal work is
// contributed, the maximum possible locktime should be enforced.

// Similarly, if the maximum amount of work is contributed, then the UTXO
// should be spendable immediately. Taking these two points on our spectrum,
// there is an infinite number of mappings we can choose. Linear? Exponential?
// Something else?

// The appeal of a linear mapping shines through. Why?
// The expected work required per trial is a pure function of the signature
// algorithm, and when an implementation of that algorithm is close to optimally
// efficient (which is to eventually be expected, similar to how bitcoin mining
// evolved from innefficient cpu mining to efficient acis), the work cost per 
// trial will become nearly constant.

// If we assume that the work cost per trial is constant, and we know the amount
// of sats which are work-locked by the UTXO, then we may be able to do some sort
// of net-present-value type of analysis in order to determine the necessary
// parameters for the work-lock.

// TODO: calculate example timelock parameter for mainnet bitcoin as of
//       block number 739626.

// TODO: can a mining pool be constructed so as to share in the work and share
//       in the rewards? Can it be trustless? The miner of a work-locked utxo
//       spends the work-locked coins as an input and, therefore (with current
//       bitcoin consensus rules), can construct a spending transaction which
//       has many outputs, and splits the reward with other miners. Of course
//       in order to apportion the reward, the miner needs to know about other
//       miners. 
//       
//       Let us imagine that a miner has solved a work-locked utxo. Here "solved"
//       means that the miner now possesses what it believes to be the transaction
//       with the soonest (shortest) locktime, relative to all other spending 
//       transactions found by other miners. Even though the transaction is not
//       yet broadcastable on the bitcoin network, similar to how bitcoin itself
//       works, it may still be rationally in the best interest of the miner to
//       propagate the transaction among other nodes which are participating in
//       this strange "work-locked utxo" game. Some of these nodes may also be 
//       miners, or, in other words, competition. 
//       
//       Here are a couple things a miner might consider doing when constructing
//       a spending transaction for a work-locked utxo:
//            1) Create an incentive for other miners to stop mining the original
//               by include an additional output which is also work-locked
//               yet with the work-lock less than that of the original utxo.
//            2) Include one or more outputs paying participants which may have
//               formed some sort of mining pool.
//            3) Include a fee for mainchain bitcoin miners.
//
//       If we squint, we can see an inkling of a new blockchain which is somehow
//       still associated with mainchain bitcoin, yet could have its own semantics.
//       Even more interesting is that, in some ways, this new chain is pre-funded
//       and only when the work-lock is surmounted does it actually interact with
//       the mainchain. The mining race is ultimately for choice of genesis block 
//       rather than for choice of the "next block." Even if participants are 
//       building additional transactions, work-locked or otherwise, on top of
//       this work-locked utxo, they risk all of those transactions becoming
//       worthless if a different genesis block is ultimately chosen. It is a
//       quite reckless and strange type of blockchain, but maybe it will be
//       useful?

