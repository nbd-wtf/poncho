package crypto

import scala.scalanative.unsigned._
import scodec.bits.ByteVector
import secp256k1.Keys
import sha256.Sha256

import codecs.{ByteVector32, ByteVector64}

type PublicKey = ByteVector
type PrivateKey = ByteVector32
type Signature = ByteVector64

object Crypto {
  def sha256(x: ByteVector): ByteVector32 =
    ByteVector32(
      ByteVector(
        Sha256.sha256(x.toArray.map[UByte](_.toUByte)).map[Byte](_.toByte)
      )
    )

  def sign(data: ByteVector, privateKey: PrivateKey): Signature =
    ByteVector64(
      ByteVector(
        Keys
          .loadPrivateKey(privateKey.toArray.map(_.toUByte))
          .toOption
          .get
          .sign(data.toArray.map(_.toUByte))
          .toOption
          .get
          .map[Byte](_.toByte)
      )
    )

  def verifySignature(
      data: ByteVector,
      signature: Signature,
      publicKey: PublicKey
  ): Boolean = {
    Keys
      .loadPublicKey(publicKey.toArray.map(_.toUByte))
      .flatMap(
        _.verify(
          data.toArray.map[UByte](_.toUByte),
          signature.toArray.map[UByte](_.toUByte)
        )
      )
      .toOption
      .getOrElse(false)
  }
}
