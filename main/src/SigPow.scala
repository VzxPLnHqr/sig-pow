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
        List.range(start = 0, end = num_keys).traverse{
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
        case ((pubKey, sigLength),i) => {
            // for the lower powers of 2, need slightly different encoding
            val powerOf2 = i match {
                case 0 => Script.write(OP_1 :: Nil)
                case 1 => Script.write(OP_2 :: Nil)
                case 2 => Script.write(OP_4 :: Nil)
                case 3 => Script.write(OP_8 :: Nil)
                case 4 => Script.write(OP_16 :: Nil)
                case n => Script.encodeNumber(spire.math.pow(2.toLong,n.toLong))
            }
            (
                OP_SIZE :: //assumes stack is .... <pubkey i> <sig_i>
                OP_PUSHDATA(Script.encodeNumber(sigLength)) :: 
                OP_LESSTHANOREQUAL :: 
                OP_IF :: OP_PUSHDATA(Script.encodeNumber(0L)) :: // work must have been done!
                OP_ELSE :: OP_PUSHDATA(powerOf2) :: // work not done, add corresponding amount to nLocktime
                OP_ENDIF :: 
                OP_FROMALTSTACK :: OP_ADD :: OP_TOALTSTACK ::
                OP_PUSHDATA(pubKey) :: OP_CHECKSIGVERIFY ::
                Nil
            )
        }.pure[F]}.map(_.flatten)
        .map(script => OP_PUSHDATA(Script.encodeNumber(minLocktimeBlocks)) :: OP_TOALTSTACK :: Nil ++ script) // before execution put `minLocktimeBlocks` on the alt stack
        .map(_ ++ (OP_FROMALTSTACK :: OP_CHECKLOCKTIMEVERIFY :: Nil)) // after execution, get accumulated nLocktime from the altstack
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
        Script.write(Script.pay2wsh(redeemScript)).pure[F]

    def witness[F[_] : Monad](redeemScript: ByteVector, signatures: List[ByteVector]) = 
        ScriptWitness(ByteVector.empty :: signatures.reverse.toSeq.appended(redeemScript)).pure[F]

    def fakeP2WSHFundingTx[F[_] : Monad](amountSats: Long, redeemScript: ByteVector):F[Transaction] = 
        Transaction(
            version=1L, 
            txIn = Seq(TxIn.coinbase(OP_1 :: OP_1 :: Nil)), 
            txOut = Seq(TxOut(Satoshi(amountSats),Script.pay2wsh(redeemScript))),
            lockTime = 0L
        ).pure[F]

    def fakeSignatures[F[_] : Monad](privKeys: List[PrivateKey], sigSizes: List[Int]) = 
        privKeys.zip(sigSizes).zipWithIndex.traverse{ case ((privKey,sigSize),i) =>
            (s"sig$i,pubkey$i",ByteVector(Array.fill(sigSize)(i.toByte)),privKey.publicKey).pure[F]    
        }

    def unsignedSpendingTx[F[_] : Monad](
                            outpoint: OutPoint,
                            spendToPubKeyScriptBytes: ByteVector,
                            targetLocktime: Long,
                            spendToAmt: Long): F[Transaction] = {
        Transaction(
            version = 1L,
            txIn = Seq(TxIn(outpoint, signatureScript = ByteVector.empty, sequence = 0xffffffffL)),
            txOut = Seq(TxOut(Satoshi(spendToAmt),spendToPubKeyScriptBytes)),
            lockTime = targetLocktime
        ).pure[F]
    }

    def signInput[F[_]:Monad](
            unsignedTx: Transaction, 
            inputIndex: Int, 
            pubKeyScript: ByteVector, 
            inputAmt: Satoshi, 
            privKey: PrivateKey): F[ByteVector] = 
        Transaction.signInput(
                tx = unsignedTx,
                inputIndex = inputIndex,
                previousOutputScript = pubKeyScript,
                SIGHASH_ALL,
                inputAmt,
                SigVersion.SIGVERSION_WITNESS_V0,
                privKey
        ).pure[F]
}