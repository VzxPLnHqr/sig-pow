package vzxplnhqr.sigpow

import fr.acinq.bitcoin._
import fr.acinq.bitcoin.Crypto._
import scodec.bits._
import spire.math._
import spire.implicits._

import cats.effect._ 

object Main extends IOApp.Simple {

    def prompt(msg: String): IO[String] = IO.print(msg + " ") >> IO.readLine

    val run = for {
        _ <- IO.println("""| This program will create an output (and an address
                           | you can send funds to) which is work-locked.
                           | A work-lock is a way of encumbering a utxo such that
                           | an expected amount of work needs to be done before
                           | the sats are spendable.
                           |
                           | For this proof of concept, we use ecdsa signature 
                           | length to encode a work-lock, and parameterize the
                           | lock such that a set of shorter ecdsa signatures will
                           | unlock the utxo sooner than a set of longer signatures.
                           | 
                           | This basically means, if a prospective spender has been
                           | furnished with the private keys necessary to generate 
                           | valid signatures, and works hard enough (to create
                           | small signatures), they are finding a spending transaction
                           | with a sooner nLocktime than one with longer signatures.
                           |
                           | A locktime, in blocks, is represented by a number 
                           | between 0 and 5,000,000.
                           | 
                           | Realistically we do not want a locktime less than the current
                           | block height, so we want prospective signers to only
                           | find valid transactions with locktimes between <current blockheight>
                           | and <max blockheight>. Choosing these numbers defines
                           | the range of possible locktimes, and the size of that
                           | range tells us how many signatures are necessary
                           | to represent every number in that range.
                           |
                           | We need to generate some private keys. Prospective
                           | spenders will use these keys (so long as you have shared
                           | them, or made them public somehow) to generate signatures in an
                           | effort to find a set of short signatures.
                           |
                           | To make the game fair, these keys can be generated
                           | from a seed which contains some hard to fake reference
                           | to a point in time. A good example might be the 
                           | blockheader from a recent bitcoin block. This means
                           | participants can have confidence that whosoever funded
                           | the work-lock output could not easily pre-compute a
                           | solution.
                        """.stripMargin)
        seed <- prompt("What seed (recent block header?) shall we use to generate the private key(s)?")
        h_seed = Crypto.sha256(ByteVector(seed.getBytes("UTF-8")))
        _ <- IO.println(s"using sha256($seed) == ${h_seed.toHex} as seed")
        cur_blockheight <- prompt("What is the current block height?").map(_.toInt)
        max_blockheight <- prompt("What is the maximum nLocktime (5000000?) you will allow?").map(_.toInt)
        block_range = max_blockheight - cur_blockheight
        _ <- IO.println(s"this gives a range of $block_range possible nLocktimes")
        num_sigs = log(block_range.toDouble,2).ceil.toInt
        _ <- IO.println(s"we will need $num_sigs signatures to encode a number in this range")
        _ <- IO.println(s"the private keys (in hex) are:")
        priv_keys <- generateKeysfromHashedSeed(num_sigs,h_seed)
        _ <- IO.println(priv_keys.map(_.value.toHex).zipWithIndex.mkString("\n"))
    } yield ()


    /**
      * Helper functions
      */

    /**
      * Deterministically generates a list of keys from a hashed seed. This is a
      * very naive implementation right now that simply appends a byte to the seed
      * and hashes it.
      *
      * @param num_keys
      * @param hashed_seed
      * @return
      */
    def generateKeysfromHashedSeed(num_keys: Int, hashed_seed: ByteVector32): IO[List[PrivateKey]] =
        IO.parTraverseN(num_keys - 1)((1 to num_keys).toList){
            i => IO(Crypto.sha256(hashed_seed.bytes ++ ByteVector(i.toByte))).map(k => PrivateKey.fromBin(k)._1)
        }
}