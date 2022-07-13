package vzxplnhqr.sigpow

import cats._
import cats.syntax.all._

import fr.acinq.bitcoin._
import fr.acinq.bitcoin.Crypto._

import scodec.bits._

object SigPow {



    /**
      * Deterministically generates a list of keys from a hashed seed. This is a
      * very naive implementation right now that simply appends a byte to the seed
      * and hashes it.
      *
      * @param num_keys
      * @param hashed_seed
      * @return
      */
    def generateKeysfromHashedSeed[F[_] : Monad](num_keys: Int, hashed_seed: ByteVector32): F[List[PrivateKey]] =
        List.range(start = 1, end = num_keys).traverse{
            i => Monad[F].pure(Crypto.sha256(hashed_seed.bytes ++ ByteVector(i.toByte))).map(k => PrivateKey.fromBin(k)._1)
        }

    /**
      * Generate the redeemScript for this work-locked (aka SigPow) output. To make
      * the transaction fit into `isStandard` rules of consensus, the redeem script
      * will need to be wrapped p2wsh style.
      * 
      * <pubkey_i> <sig_i> 
      */
    def redeemScript[F[_]: Monad]( sigLengths: List[(PublicKey,Int)], minLocktimeBlocks: Long = 0L ) = sigLengths.zipWithIndex.traverse{
        case ((pubKey, sigLength),i) => (
            OP_TOALTSTACK :: //move the first item (the nLocktime accumulator to the altstack)
            OP_SIZE :: //assumes stack is .... <pubkey i> <sig_i>
            OP_PUSHDATA(Script.encodeNumber(sigLength)) :: 
            OP_LESSTHANOREQUAL :: 
            OP_IF :: OP_PUSHDATA(Script.encodeNumber(0L)) :: // work must have been done!
            OP_ELSE :: OP_PUSHDATA(Script.encodeNumber(spire.math.pow(2L,i.toLong))) :: // work not done, add corresponding amount to nLocktime
            OP_ENDIF :: 
            OP_FROMALTSTACK :: OP_ADD :: //assumes that what is on top of the stack now is the accumulated nLocktime so far
            OP_PUSHDATA(pubKey) :: OP_CHECKSIGVERIFY ::
            Nil
        ).pure[F]}.map(_.flatten)
        .map(_.prepended(OP_PUSHDATA(Script.encodeNumber(minLocktimeBlocks)))) // before execution put `minLocktimeBlocks` at the beginning (which will get moved to the alt stack and accumulate)
        .map(_.appended(OP_CHECKLOCKTIMEVERIFY)) // after execution, should just have nLocktime on the stack now
        .map(Script.write(_))

    /**
      * The sigScript is a simple push of all the signatures
      * to be verified. The order must match the order in which `redeemScript`
      * will be executed since the `redeemScript` is what hard codes the
      * public keys.
      *
      * @param signatures
      * @return
      */
    def sigScript[F[_]: Monad](signatures: List[ByteVector]) =
        signatures.traverse{
            case sig => OP_PUSHDATA(sig) :: Nil
        }

    def pubKeyScript[F[_] : Monad](redeemScript: ByteVector) =
        Script.write(Script.pay2wsh(redeemScript)).pure[F] //Script.write(OP_0 :: OP_PUSHDATA(Crypto.sha256(redeemScript)) :: Nil).pure[F]

    def witness[F[_] : Monad](redeemScript: ByteVector, signatures: List[ByteVector]) = 
        ScriptWitness(signatures.toSeq.appended(redeemScript)).pure[F]

    def fakeFundingTx[F[_] : Monad](amountSats: Long, pubKeyScript: ByteVector) = 
        Transaction(
            version=1, 
            txIn = Seq(TxIn.coinbase(OP_1 :: OP_1 :: Nil)), 
            txOut = Seq(TxOut(Satoshi(amountSats),pubKeyScript)),
            lockTime = 0L
        ).pure[F]
}