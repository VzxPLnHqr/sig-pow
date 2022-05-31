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
// Let us assume that these initial sats are sent to us in a normal way
// via a p2pkh payment.
// Let us name ourselves Alice.

object Alice {
// We start with a private key, generate a public key, and an address.
val privateKey = PrivateKey.fromBase58("cRp4uUnreGMZN8vB7nQFX6XWMHU5Lc73HMAhmcDEwHfbgRS66Cqp", Base58.Prefix.SecretKeyTestnet)._1
val publicKey = privateKey.publicKey
val pubKeyScript = Script.write(Script.pay2pkh(publicKey))
val address = pubKeyScript.toBase58

// the corresponding sigScript
def sigScript(sig:ByteVector) = OP_PUSHDATA(sig) :: OP_PUSHDATA(publicKey) :: Nil

// this is basically just a wrapper around Transaction.signInput(...) function
// provided by the underlying library
def signInput(tx: Transaction, inputIndex: Int, previousOutputScript: ByteVector, sighashType: Int, amount: Satoshi, signatureVersion: Int): ByteVector =
  Transaction.signInput(tx,inputIndex,previousOutputScript, sighashType, amount, signatureVersion, privateKey)
}

// ....time passes ..... somebody creates a transaction which sends 
// 10,000 sats to Alice's address. How do we know?
// We see the following transaction confirmed in the blockchain.
// Our sats are in the first output (index 0) of this transaction
// We see that the address of the first output (index 0) matches Alice's address.
// We can then extract out the amount of sats which were sent.
val previousTx = Transaction.read("0100000001b021a77dcaad3a2da6f1611d2403e1298a902af8567c25d6e65073f6b52ef12d000000006a473044022056156e9f0ad7506621bc1eb963f5133d06d7259e27b13fcb2803f39c7787a81c022056325330585e4be39bcf63af8090a2deff265bc29a3fb9b4bf7a31426d9798150121022dfb538041f111bb16402aa83bd6a3771fa8aa0e5e9b0b549674857fafaf4fe0ffffffff0210270000000000001976a91415c23e7f4f919e9ff554ec585cb2a67df952397488ac3c9d1000000000001976a9148982824e057ccc8d4591982df71aa9220236a63888ac00000000")
assert(previousTx.txOut(0).publicKeyScript.toBase58 == Alice.address) // address matches!
assert(previousTx.txOut(0).amount == (10000 sat)) // looks like we received 10000 sats!

// Great! We now have some sats which we can spend. We will create a new
// transaction with a more complicated pubkeyScript:
//    OP_SIZE OP_CHECKSEQUENCEVERIFY OP_DROP OP_CHECKSIGVERIFY
// from https://gist.github.com/RobinLinus/95de641ed1e3d9fde83bdcf5ac289ce9
//
// Here we encapsulate this script template in an object called SigPowTx
object SigPowTx {
  val pubKeyScript = OP_SWAP :: OP_SIZE :: OP_CHECKSEQUENCEVERIFY :: OP_DROP :: OP_SWAP :: OP_CHECKSIGVERIFY :: OP_1 :: Nil
                          //note: if we leave the needless OP_1 off, then this bitcoin-lib
                          // scala library from ACINQ seems to think 
                          // the stack would be empty. However, the script verifies 
                          // just fine in btcdeb, possibly a bug in acinq bitcoin-lib?  

  def sigScript(sig:ByteVector, pubKey: PublicKey) = OP_PUSHDATA(sig) :: OP_PUSHDATA(pubKey) :: Nil

  // for simplicity we set this up so that all miners are using the same private key to mine
  // this might be the most fair way to do it
  val privKey = PrivateKey.fromBase58("cRp4uUnreGMZN8vB7nQFX6XWMHU5Lc73HMAhmcDEwHfbgRS66Cqp", Base58.Prefix.SecretKeyTestnet)._1
}

// Next, we create a transaction where the sig script is the pubkey script of the tx
// we want to redeem (the funding input). This is a somewhat confusing, because 
// it needs to happen in the following steps:
// 
// (1) create the transaction which takes as input the outPoint we seek to spend
//        leaving the signatureScript field empty (Nil) for now. 
//  
// (2) then sign the input, thereby resulting in a fully signed transaction
val unsignedTx1 = Transaction(
  version = 1L,
  txIn = List(
    TxIn(OutPoint(previousTx, 0), signatureScript = Nil, sequence = 0xFFFFFFFFL)
  ),
  txOut = List (
    // we will just send the full amount available
    // notice how the publicKeyScript for the output of this new transaction
    // is now derived from the SigPowTx template.
    TxOut(amount = 10000 sat, publicKeyScript = SigPowTx.pubKeyScript)
  ),
  lockTime = 0L
)

// Now we get Alice's signature.
val sigAlice = Alice.signInput(unsignedTx1,0,previousTx.txOut(0).publicKeyScript, SIGHASH_ALL, 10000 sat, SigVersion.SIGVERSION_BASE)

// As a bit of foreshadowing, notice the length of Alice's signature. 
// With high liklihood it will be one of 71, 72, or 73 bytes in length,
// but if she was extremely lucky or (more likely) put in a lot of work
// generating a bunch of valid signatures and only giving us a short one,
// she could get down into the 60s.
assert(sigAlice.length >= 71)

// Anyway, with Alice's signature, we can now update the transaction to include her signature
// to do this, we take her signature and put it into a sigScript at the appropriate index
// sigScript's can sometimes be more complicated, but in this case the sigScript is very
// simple. It just pushes Alice's signature, and Alice's public key onto the stack.
val signedTx1 = unsignedTx1.updateSigScript(0,OP_PUSHDATA(sigAlice) :: OP_PUSHDATA(Alice.publicKey) :: Nil)
signedTx1.isFinal(100L,100L)
// We now have a signed transaction, but before we broadcast it, we need to check if it is valid.
Transaction.correctlySpends(signedTx1,Seq(previousTx),ScriptFlags.MANDATORY_SCRIPT_VERIFY_FLAGS)
// Great! If we got here without any errors being thrown, then the transaction passed the
// various validity checks, and we should now be able to broadcast the signed transaction.

val signedBroadcastableTx1_hex = signedTx1.toString
// ....transaction broadcast...time passes...transaction confirmed...
// 
// The sats are now locked up in a manner such that someone who produces a shorter, yet valid,
// signature will be able to redeem the coins sooner. 
// Bob and Charlie both want to compete for these work-locked sats.
// To do so, they each construct a transaction which spends the work-locked sats.
// Since Bob and Charlie will both be doing essentially the same thing, we can
// consider them both SigPowMiner's and abstract out some functionality.

object SigPowMiner {
  def buildClaimTx(prevOut: OutPoint, amount: Satoshi, pubKey: PublicKey, nonce: ByteVector) = Transaction(
    version = 2L, //note: version 2 is necessary here for OP_CSV to validate properly
    txIn = List(
      TxIn(prevOut, signatureScript = Nil, sequence = 100L ) // note: sequence must be greater than 73 (73 bytes is largest ecdsa signature)
    ),
    txOut = List(
      // first output is for claiming the sats
      TxOut(amount = amount, Script.write(Script.pay2pkh(pubKey))),
      // for now we just provie a nonce area via op_return, there are probably
      // far more clever things we can do in the future instead
      TxOut(amount = 0 sat, OP_RETURN :: OP_PUSHDATA(nonce) :: Nil)
    ),
    //note: lockTime probably should be a function of signature length...still working through how to do that though
    lockTime = 0L //setting to 100 since the transaction with the output it is spending has locktime 0
  )
  
  // here we assume that the passed in transaction has its first input
  // (index 0) conforming to the SigPowTx template. We need to sign
  // this input with the given private key
  // the default targetSigLength of 73 bytes means that no real work will
  // be performed (the first signature should suffice)
  // 
  def signClaimTx(tx: Transaction): (Transaction,Int) = {
    
      val sig = Transaction.signInput(tx,0,SigPowTx.pubKeyScript,SIGHASH_ALL, 10000 sat, SigVersion.SIGVERSION_BASE, SigPowTx.privKey)
      (tx.updateSigScript(0,SigPowTx.sigScript(sig,SigPowTx.privKey.publicKey)),sig.length.toInt)
  }
}

object Bob {
    //val privateKey = PrivateKey.fromBase58("cRp4uUnreGMZN8vB7nQFX6XWMHU5Lc73HMAhmcDEwHfbgRS66Cqp", Base58.Prefix.SecretKeyTestnet)._1
    val privateKey = PrivateKey.fromBin(Crypto.sha256(ByteVector("abc".getBytes)))._1
}

val unsignedClaim = SigPowMiner.buildClaimTx(OutPoint(signedTx1,0),10000 sat, Bob.privateKey.publicKey, ByteVector(Array[Byte](1)))
val signedClaim = SigPowMiner.signClaimTx(unsignedClaim)

// printing out the transactions invovled for easy paste into btcdeb too
println("previousTx------->>>>>> spent by Tx1---------------->>>>>>------------------------")
println(s"btcdeb --tx=$signedTx1 --txin=$previousTx")
println("now spending Tx1 with little work ~73byte sig -------------------------------------")
println(s"btcdeb --tx=${signedClaim._1} --txin=$signedTx1")

// if the line below does not throw an error, then
// the transaction is probably valid and can be broadcast (if it meets network standardness)
Transaction.correctlySpends(signedClaim._1,Seq(signedTx1),ScriptFlags.MANDATORY_SCRIPT_VERIFY_FLAGS)

// a naive mining implementation
def mine(outpoint: OutPoint, minerPubKey: PublicKey, targetSigLength: Int = 73): Transaction = {
  def inner(nonce: BigInt):Transaction = {
    val unsigned = SigPowMiner.buildClaimTx(outpoint,10000 sat, minerPubKey,ByteVector(nonce.toByteArray))
    val signed = SigPowMiner.signClaimTx(unsigned)
    if (signed._2 <= targetSigLength) {
      println(s"found! nonce: $nonce")
      signed._1
    } else
      inner(nonce + 1)
  }
  inner(BigInt(1))
}

def testMine(targetSigLength: Int) = mine(OutPoint(signedTx1,0),Bob.privateKey.publicKey,targetSigLength)

// successfully mined signature with length less than or equal to 68 bytes! Took a couple minutes on a laptop.
val txWith68byteSig = "0200000001f6e2a439a8e02392095f0c3bf866aeca3d33625d8163c571cc908271264da77200000000674430410220324cc4c73b47357a3b9ee4c7aa906b910d4f89221c9d52e3fddfc88917f552b7021d46ea8179590fa7f6600f6e9e242b6a563a97ed63719227c4583a1ed098012103144d434e85140d4109814ac78491ffeae384c18e2225ba109ad25ff0e46eef65640000000210270000000000001976a914fa19739677ed143ba2dcabf535aebc043cd40cdc88ac0000000000000000056a0323e30d00000000"
println("-----------spending Tx1 with 68 byte signature-----------------")
println(s"btcdeb --tx=$txWith68byteSig --txin=$signedTx1")

/**
 *  example run with 68 byte signature (took a couple minutes on laptop)
  * btcdeb --tx=0200000001f6e2a439a8e02392095f0c3bf866aeca3d33625d8163c571cc908271264da77200000000674430410220324cc4c73b47357a3b9ee4c7aa906b910d4f89221c9d52e3fddfc88917f552b7021d46ea8179590fa7f6600f6e9e242b6a563a97ed63719227c4583a1ed098012103144d434e85140d4109814ac78491ffeae384c18e2225ba109ad25ff0e46eef65640000000210270000000000001976a914fa19739677ed143ba2dcabf535aebc043cd40cdc88ac0000000000000000056a0323e30d00000000 --txin=010000000114bc392e595b03e7fa0fbebe2ee2668930a9c1314c2d0d1d0d4bb60eb51822dd000000006a4730440220423df4038c681a1214df160119c7fb2bfb6a66c84cf9ce10949df81a36da8691022016ec048ff553a0d21c36dfd7e4dda07a1d9956b7f4b84cd8de3d9f16c853143b012103144d434e85140d4109814ac78491ffeae384c18e2225ba109ad25ff0e46eef65ffffffff011027000000000000077c82b2757cad5100000000
  */