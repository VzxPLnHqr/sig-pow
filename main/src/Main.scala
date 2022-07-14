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
        _ <- IO.println("""| Please enter one of the following commands:
                            | lock - This will guide through creating a work-locked utxo.
                            |
                            | unlock - If you already have information about a work-locked utxo
                            |          and want to try to mine (solve/spend) it, choose this option.
                            |
                            |""".stripMargin)
        cmd = prompt("enter comand (default: lock)","lock")(s => s)
        _ <- cmd.flatMap {
          case "lock" => createWorkLockedOutput
          case "unlock" =>
            IO.println("""| So, you know about a work-locked utxo that you would like to
                          | unlock. You will need a few things to do so:
                          |
                          |  1. the full transaction (in hex) which funded 
                          |     work-locked output.
                          |
                          |  2. the output index in that transaction which contains
                          |     the worklocked sats (typically this is index 0)
                          |
                          |  3. the "seed string" which was used to generate the private 
                          |     keys needed to sign the spending transaction which
                          |     we will construct (and attempt to mine) here.
                          |
                          |  4. a list of addresses and ammounts which you would 
                          |     like to send the funds too.
                          |
                          |""".stripMargin) >> 
            prompt("Enter full hex for funding transaction:")
              .map(Transaction.read(_)).flatMap(t => spendWorkLockedOutput(t))
        }
    } yield ()

    val createWorkLockedOutput = for {
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
        seed <- prompt("What seed (recent block header?) shall we use to generate the private key(s)? (default: abc)","abc")(s => s)
        h_seed = Crypto.sha256(ByteVector(seed.getBytes("UTF-8")))
        _ <- IO.println(s"Great! Using sha256($seed) == ${h_seed.toHex} as seed")
        cur_blockheight <- prompt("What is the current block height? (default: 744609)", 744609)(_.toInt)
        max_blockheight <- prompt("What is the maximum nLocktime you will allow? (default: 5000000)", 5000000)(_.toInt)
        block_range = max_blockheight - cur_blockheight
        _ <- IO.println(s"this gives a range of $block_range possible nLocktimes")
        num_sigs <- IO(log(block_range.toDouble,2).ceil.toInt)
        _ <- IO.println(s"we will need $num_sigs signatures to encode a number in this range")
        priv_keys <- SigPow.generateKeysfromHashedSeed[IO](num_sigs,h_seed)
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
         custom_sizes <- promptBool("Do you want to pick your own sizes? (y/n) (default: n)",false)
         sig_sizes <- if (custom_sizes) {
                        List.range(0,num_sigs).traverse{ i => prompt(s"max sig length for sig $i?").map(_.toInt)}
                      } else List.range(0,num_sigs).traverse(_ => IO(70)) // 70 byte sigs are easy for testing purposes
         _ <- IO.println("""| Ok, here are the private keys, public keys, and max signature lengths
                            | needed in order to reduce the locktime for a spending transaction
                            | down to zero (no locktime). Good luck!
                            |""".stripMargin)
         _ <- IO.println("(index_i, private_key_i, pub_key_i, max_siglength_bytes_i)")
         _ <- priv_keys.zip(sig_sizes).zipWithIndex
                .traverse{ case ((k,s),i) => IO.println(s"($i, ${k.value.toHex}, ${k.publicKey}, $s)")}
         minLocktime <- prompt("Is there a minimum nLocktime you want to enforce? (default: 0)",0)(_.toInt)
         redeemScript <- SigPow.redeemScript[IO](priv_keys.map(_.publicKey).zip(sig_sizes),minLocktime)
         _ <- IO.println(s"redeem script (in hex): ${redeemScript.toHex} \n")
         pubKeyScript_p2wsh <- SigPow.pubKeyScript[IO](redeemScript)
         _ <- IO.println(s"p2wsh pubkey script (in hex): ${pubKeyScript_p2wsh.toHex}")
         address_mainnet = Base58Check.encode(Base58.Prefix.ScriptAddress,Crypto.hash160(pubKeyScript_p2wsh))
         address_testnet = Base58Check.encode(Base58.Prefix.ScriptAddressTestnet,Crypto.hash160(pubKeyScript_p2wsh))
         address_signet = Base58Check.encode(Base58.Prefix.ScriptAddressSegnet,Crypto.hash160(pubKeyScript_p2wsh))
         _ <- IO.println(s"mainnet address: $address_mainnet")
         _ <- IO.println(s"testnet address: $address_testnet")
         _ <- IO.println(s"signet address: $address_signet")
         sendFakeFunds <- promptBool("Shall we build a fake coinbase transaction that sends 1,000,000 sats to this address so you can debug? (default: y)",true)
         fundingTx <- if(sendFakeFunds) {
              SigPow.fakeP2WSHFundingTx[IO](1000000L,redeemScript).flatTap(t => 
                IO.println(s"funding transaction txid: ${t.txid}") >>
                IO.println(s"the work-locked sats are at output index 0 wrapped in P2WSH") >> 
                IO.println(s"funding transaction (hex): $t") >>
                IO.println("1,000,000 sats now (fake) work-locked!"))
          } else { 
                prompt("You now need to fund the work-lock! Please enter the full hex for the funding transaction:").map(hex => Transaction.read(hex))
          }
         //fakeSigs <- SigPow.fakeSignatures[IO](priv_keys,sig_sizes.map(_ => 70)) //note: fake sig sizes here
         //_ <- IO.println(s"here are $num_sigs fake sig_i:pubkey_i pairs (useful for debugging with btcdeb):")
         //_ <- IO.println(fakeSigs.map{ case (_, sig_i, pub_i) => s"${sig_i.toHex}:$pub_i"}.mkString(","))
         //_ <- IO.println(s"here is a fake stack of signatures (useful for debugging with btcdeb):")
         //_ <- IO.println(fakeSigs.map{ case (_, sig_i, _) => sig_i.toHex }.reverse.mkString(" "))
         _ <- IO.println("End locking procedure.")
         continue <- promptBool("continue to unlocking procedure? (y/n) (default: y)",true)
         _ <- if(continue) spendWorkLockedOutput(fundingTx) else IO.unit
    } yield (fundingTx)

    def spendWorkLockedOutput(fundingTx: Transaction): IO[Transaction] = for {
      _ <- IO.unit
      //fundingTx <- prompt("Please enter the hex for the full funding transaction:").map(hex => Transaction.read(hex))
      fundingTxid = fundingTx.txid
      _ <- IO.println(s"got txid: ${fundingTxid.toHex}")
      fundingOutputIndex <- prompt("What is the output index of the funding transaction which has the work-lock? (default: 0)",0)(_.toInt)
      fundingOutpoint <- IO(OutPoint(fundingTx,fundingOutputIndex))
      worklockedSatsAmt = fundingTx.txOut(fundingOutputIndex).amount.toLong
      _ <- IO.println(s"output index $fundingOutputIndex has $worklockedSatsAmt sats work-locked!")
      seedString <- prompt("What is the seed string which was used to generate the private keys? (default: abc)","abc")(s => s)
      h_seed = Crypto.sha256(ByteVector(seedString.getBytes("UTF-8")))
      num_sigs <- prompt("How many private keys do we need? (default: 23)",23)(_.toInt)
      sig_sizes <- prompt("What are the signature lengths, in bytes, for each of the keys? Example: 70,69,68...70 (default: all 70)",List.fill(num_sigs)(70))(_.split(",").toList.map(_.toInt))
      priv_keys <- SigPow.generateKeysfromHashedSeed[IO](num_sigs,h_seed)
      _ <- IO.println("(index_i, private_key_i, pub_key_i, max_siglength_bytes_i)")
      _ <- priv_keys.zip(sig_sizes).zipWithIndex
                .traverse{ case ((k,s),i) => IO.println(s"($i, ${k.value.toHex}, ${k.publicKey}, $s)")}
      minLocktime <- prompt("What is the minimum locktime for the redeemScript (default: 0)",0)(_.toInt)
      redeemScript <- SigPow.redeemScript[IO](priv_keys.map(_.publicKey).zip(sig_sizes),minLocktime)
      _ <- IO.println(s"reconstructed redeem script (in hex): ${redeemScript.toHex} \n")
      pubKeyScript_p2wsh <- SigPow.pubKeyScript[IO](redeemScript)
      _ <- IO.println(s"reconstructed the p2wsh pubkey script (in hex): ${pubKeyScript_p2wsh.toHex}")
      _ <- IO.println(s"-------- now lets start mining ------")
      _ <- IO.println("""| You now need to specify a "target" locktime which you want
                         | your spending transaction to achieve. If you want to be able to
                         | broadcast/spend these work-locked coins immediately, that
                         | means you want a nLocktime of 0 blocks. Note, however,
                         | that a nLocktime of 0 will mean that you will need to put enough
                         | work into *each* signature such that the bit associated with that
                         | signature length in the binary encoding of the locktime calculated
                         | by the redeemScript will be a zero bit. If all signature lengths
                         | are short enough, and the redeemScript does not have a minimum
                         | nLocktime set (you have already been asked this), then you will 
                         | be able to spend the work-locked coins immediately by broadcasting
                         | to the network.
                         | """".stripMargin)
      targetLocktime <- prompt("What is the target locktime (in blocks) you would like to achieve? (default: 0)",0L)(_.toLong)
      spendToPubKeyScriptBytes <- prompt("\nWhich pubkeyScript would you like to send the unlocked coins to? (default: 0014fa19739677ed143ba2dcabf535aebc043cd40cdc)",ByteVector.fromHex("0014fa19739677ed143ba2dcabf535aebc043cd40cdc").get)(ByteVector.fromHex(_).get)
      spendToAmt <- prompt(s"Of the $worklockedSatsAmt sats, how many to send to that address? (default: $worklockedSatsAmt)",worklockedSatsAmt)(_.toLong)
      unsignedSpendingTx <- SigPow.unsignedSpendingTx[IO](fundingOutpoint,spendToPubKeyScriptBytes,targetLocktime,spendToAmt)
      _ <- IO.println(s"unsigned spending transaction (hex): $unsignedSpendingTx")
      signatures <- priv_keys.traverse{k => SigPow.signInput[IO](unsignedSpendingTx,fundingOutputIndex,redeemScript,Satoshi(worklockedSatsAmt),k)}
      _ <- priv_keys.map(_.publicKey.value.toHex).zip(signatures).traverse{ case (pub,sig) => IO.println(s"$pub --sig--> ${sig.toHex}")}
      witness <- SigPow.witness[IO](redeemScript,signatures)
      signedSpendingTx = unsignedSpendingTx.updateWitness(0,witness)
      _ <- IO.println(s"SIGNED spending transaction (hex): $signedSpendingTx")
      _ <- IO.println("Done.")
      _ <- IO(Transaction.correctlySpends(signedSpendingTx,Seq(fundingTx),ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)).onError(e => IO.println(e))
    } yield signedSpendingTx

    /**
      * Helper functions
      */

    def prompt(msg: String): IO[String] = IO.print(msg + " ") >> IO.readLine
    def prompt[A](msg: String, default: A)(f: => String => A): IO[A] = promptWithDefault(msg,default)(f)
    def promptBool(msg: String, default: Boolean): IO[Boolean] = prompt(msg).map(_.toUpperCase()).map{
      case "Y" | "yes" | "1" => true
      case "" => default
      case _ => false
    }
    def promptWithDefault[A](msg: String, default: A)(f: => String => A) = prompt(msg).flatMap{
      case "" => IO(default)
      case s => IO(f(s))
    }

}