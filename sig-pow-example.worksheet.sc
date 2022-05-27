// scala 2.13.8

/**
 * A quick and dirty attempt at implementing proof-of-work via
 * signature length (sig-pow) as contemplated here: 
 *  https://gist.github.com/RobinLinus/95de641ed1e3d9fde83bdcf5ac289ce9
 * */

// first we pull in acinq bitcoin-lib for doing the basic bitcoin crypto primitives
// latest version of bitcoin-lib is 0.23, but here we use 0.20 due to weird namespace errors
import $ivy.`fr.acinq::bitcoin-lib:0.20`
import fr.acinq.bitcoin._
import fr.acinq.bitcoin.Crypto._

import scodec.bits._

import scala.language.postfixOps

// Before we can do anything fancy, we need some sats!
// Let us assume that these initial sats are sent to us in a normal way.
// We start with a private key, generate a public key, and an address.
// Then somebody sends us some sats to that address.
// We want to take those sats and move them to a new output governed by some more
// complex logic.

val privateKey = PrivateKey.fromBase58("cRp4uUnreGMZN8vB7nQFX6XWMHU5Lc73HMAhmcDEwHfbgRS66Cqp", Base58.Prefix.SecretKeyTestnet)._1
val publicKey = privateKey.publicKey
val pubKeyScriptHex = Script.write(Script.pay2pkh(publicKey)).toHex

// ....time passes ..... somebody creates a transaction which sends 
// 10,000 sats to the above address! How do we know?
// We see the following transaction confirmed in the blockchain.
// From it we can extract the amount of sats which were sent.
// Our sats are in the first output (index 0) of this transaction
val previousTx = Transaction.read("0100000001b021a77dcaad3a2da6f1611d2403e1298a902af8567c25d6e65073f6b52ef12d000000006a473044022056156e9f0ad7506621bc1eb963f5133d06d7259e27b13fcb2803f39c7787a81c022056325330585e4be39bcf63af8090a2deff265bc29a3fb9b4bf7a31426d9798150121022dfb538041f111bb16402aa83bd6a3771fa8aa0e5e9b0b549674857fafaf4fe0ffffffff0210270000000000001976a91415c23e7f4f919e9ff554ec585cb2a67df952397488ac3c9d1000000000001976a9148982824e057ccc8d4591982df71aa9220236a63888ac00000000")
assert(previousTx.txOut(0).publicKeyScript.toHex == pubKeyScriptHex)
assert(previousTx.txOut(0).amount == (10000 sat)) // received 10000 sats

// Great! We now have some sats which we can spend. We will create a new
// transaction with a more complicated pubkeyScript:
//    OP_SIZE OP_CHECKSEQUENCEVERIFY OP_DROP OP_CHECKSIGVERIFY
// from https://gist.github.com/RobinLinus/95de641ed1e3d9fde83bdcf5ac289ce9
//
// Here we encapsulate this script template in an object called SigPowTx
object SigPowTx {
  val pubKeyScript = OP_SIZE :: OP_CHECKSEQUENCEVERIFY :: OP_DROP :: OP_CHECKSIGVERIFY :: Nil
  def sigScript(sig:ByteVector, pubKey: PublicKey) = OP_PUSHDATA(sig) :: OP_PUSHDATA(pubKey) :: Nil
}

// Next, we create a transaction where the sig script is the pubkey script of the tx
// we want to redeem. This is a somewhat confusing, because it needs to happen in
// two steps: 
// (1) create the transaction with an empty signatureScript input, 
// (2) then sign the transaction and update the signatureScript field
val unsignedTx1 = Transaction(
  version = 1L,
  txIn = List(
    TxIn(OutPoint(previousTx, 0), signatureScript = Nil, sequence = 0xFFFFFFFFL)
  ),
  txOut = List (
    TxOut(amount = 10000 sat, publicKeyScript = SigPowTx.pubKeyScript)
  ),
  lockTime = 0L
)

// now sign the transaction
val sig = Transaction.signInput(unsignedTx1,0,previousTx.txOut(0).publicKeyScript, SIGHASH_ALL, 0 sat, SigVersion.SIGVERSION_BASE, privateKey)
val signedTx1 = unsignedTx1.updateSigScript(0,SigPowTx.sigScript(sig,publicKey))

Transaction.correctlySpends(signedTx1,Seq(previousTx),ScriptFlags.MANDATORY_SCRIPT_VERIFY_FLAGS)
// Great! If we got here without any errors being thrown, then we can now broadcast the signed transaction.

val signedBroadcastableTx1_hex = signedTx1.toString
// ....transaction broadcast...time passes...transaction confirmed...
// 
// The sats are now locked up in a manner such that someone who produces a shorter, yet valid,
// signature will be able to redeem the coins sooner. Now we will attempt to construct such a
// spending transaction.

// TODO