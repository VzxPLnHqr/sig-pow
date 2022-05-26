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

/**
  * The pubkeyScript we are trying to encode:
    OP_SIZE OP_CHECKSEQUENCEVERIFY OP_DROP OP_CHECKSIGVERIFY
    from https://gist.github.com/RobinLinus/95de641ed1e3d9fde83bdcf5ac289ce9
  */

val pubKeyScript = OP_SIZE :: OP_CHECKSEQUENCEVERIFY :: OP_DROP :: OP_CHECKSIGVERIFY :: Nil

def sigScript(sig:ByteVector, pubKey: PublicKey) = OP_PUSHDATA(sig) :: OP_PUSHDATA(pubKey) :: Nil

// imagine we already have a tx that was sent to a public key we own
val to = "mi1cMMSL9BZwTQZYpweE1nTmwRxScirPp3"
val (Base58.Prefix.PubkeyAddressTestnet, pubkeyHash) = Base58Check.decode(to)
val amount = 10000 sat

val privateKey = PrivateKey.fromBase58("cRp4uUnreGMZN8vB7nQFX6XWMHU5Lc73HMAhmcDEwHfbgRS66Cqp", Base58.Prefix.SecretKeyTestnet)._1
val publicKey = privateKey.publicKey

val previousTx = Transaction.read("0100000001b021a77dcaad3a2da6f1611d2403e1298a902af8567c25d6e65073f6b52ef12d000000006a473044022056156e9f0ad7506621bc1eb963f5133d06d7259e27b13fcb2803f39c7787a81c022056325330585e4be39bcf63af8090a2deff265bc29a3fb9b4bf7a31426d9798150121022dfb538041f111bb16402aa83bd6a3771fa8aa0e5e9b0b549674857fafaf4fe0ffffffff0210270000000000001976a91415c23e7f4f919e9ff554ec585cb2a67df952397488ac3c9d1000000000001976a9148982824e057ccc8d4591982df71aa9220236a63888ac00000000")

// create a transaction where the sig script is the pubkey script of the tx we want to redeem
// the pubkey script is just a wrapper around the pub key hash
// what it means is that we will sign a block of data that contains txid + from + to + amount

val tx1 = Transaction(
  version = 1L,
  txIn = List(
    TxIn(OutPoint(previousTx, 0), signatureScript = Nil, sequence = 0xFFFFFFFFL)
  ),
  txOut = List (
    TxOut(amount = amount, publicKeyScript = pubKeyScript)
  ),
  lockTime = 0L
)

// now sign the transaction
val sig = Transaction.signInput(tx1,0,previousTx.txOut(0).publicKeyScript, SIGHASH_ALL, 0 sat, SigVersion.SIGVERSION_BASE, privateKey)
val tx2 = tx1.updateSigScript(0,sigScript(sig,publicKey))

Transaction.correctlySpends(tx2,Seq(previousTx),ScriptFlags.MANDATORY_SCRIPT_VERIFY_FLAGS)
