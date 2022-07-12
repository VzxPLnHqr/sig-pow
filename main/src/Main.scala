package vzxplnhqr.sigpow

import fr.acinq.bitcoin._
import fr.acinq.bitcoin.Crypto._
import scodec.bits._
import spire.math._
import spire.implicits._

import cats.syntax.all._

import cats.effect._
import cats.effect.syntax.all._

object Main extends IOApp.Simple {
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
                           | between 0 and 5,000,000. There are also locktimes which
                           | are represented by unix timestamps in the blockheader,
                           | but they will overflow in the year 2106. We want work-locks
                           | that last longer! (though, a protocol upgrade might render
                           | the coins inaccessible, so this may be considered "reckless").
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
        _ <- IO.println(s"Great! Using sha256($seed) == ${h_seed.toHex} as seed")
        cur_blockheight <- prompt("What is the current block height? (default: 744609)", 744609)(_.toInt)
        max_blockheight <- prompt("What is the maximum nLocktime you will allow? (default: 5000000)", 5000000)(_.toInt)
        block_range = max_blockheight - cur_blockheight
        _ <- IO.println(s"this gives a range of $block_range possible nLocktimes")
        num_sigs <- IO(log(block_range.toDouble,2).ceil.toInt)
        _ <- IO.println(s"we will need $num_sigs signatures to encode a number in this range")
        _ <- IO.println(s"the private keys (in hex) are:")
        priv_keys <- generateKeysfromHashedSeed(num_sigs,h_seed)
        _ <- priv_keys.map(_.value.toHex).zipWithIndex.traverse{case (k,i) => IO.println(s"$i. $k")}
        _ <- IO.println("""| Now we need to calibrate your work-lock. A signature
                           | for each private key will be required to spend the
                           | locked sats. Imagine processing these signatures in
                           | sequence. We will choose a maximum acceptable length
                           | for each signature and encode in the locking script
                           | a check (using `OP_SIZE`) to confirm whether or not the
                           | signature is short enough.
                           |
                           | For each signature, it is either short enough, or it is
                           | not. If it is short enough, then the corresponding bit
                           | in the base-2 representation of nLocktime is assigned
                           | a "0". Then, all of these base-2 locktime bits are
                           | aggregated into final number which represents the locktime.
                           |
                           | So, each attempt at spending this transaction (by finding
                           | a bunch of small signatures, some of which are extremely
                           | hard to find) might end up with a different nLocktime.
                           | The goal, of course, is to find a set of signatures
                           | which creates the soonest nLocktime of all participants.
                           |
                           | It may be wise to work together, form a mining pool, or
                           | something clever like that. Afterall, the outputs of
                           | the spending transaction can be anything acceptable
                           | to the bitcoin network (e.g. if you are working on this
                           | with other people, consider rewarding those people in your
                           | hopeful spending transactions, and perhaps they will do the
                           | same!)
                           | 
                           | We now ask for the maximum size for each signature, starting
                           | with the "most significant signature" which, if a spender
                           | successfully finds such a signature, will set the most significant
                           | bit of the nLocktime to zero (thereby cutting the locktime in
                           | half!)
                           | 
                           | If you do not want to pick your own sizes, we can supply
                           | you with a reasonable default.
                           |
                        """.stripMargin)
         custom_sizes <- promptBool("Do you want to pick your own sizes? (y/n)")
         sig_sizes <- if (custom_sizes) {
                        List.range(1,num_sigs).traverse{ i => prompt(s"max sig length for sig $i?").map(_.toInt)}
                      } else List.range(1,num_sigs).traverse(_ => IO(70)) // 70 byte sigs are easy for testing purposes
         _ <- IO.println(s"Ok, using signature sizes:") >> sig_sizes.zipWithIndex.traverse{ case (s,i) => IO.println(s"$i. $s bytes")}
    } yield ()


    /**
      * Helper functions
      */

    def prompt(msg: String): IO[String] = IO.print(msg + " ") >> IO.readLine
    def prompt[A](msg: String, default: A)(f: => String => A): IO[A] = promptWithDefault(msg,default)(f)
    def promptBool(msg: String): IO[Boolean] = prompt(msg).map(_.toUpperCase()).map{
      case "Y" | "yes" | "1" | "y" => true
      case _ => false
    }
    def promptWithDefault[A](msg: String, default: A)(f: => String => A) = prompt(msg).flatMap{
      case "" => IO(default)
      case s => IO(f(s))
    }


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
        List.range(start = 1, end = num_keys).traverse{
            i => IO(Crypto.sha256(hashed_seed.bytes ++ ByteVector(i.toByte))).map(k => PrivateKey.fromBin(k)._1)
        }
}